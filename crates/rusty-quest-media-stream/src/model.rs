//! Media stream session data model and constants.

use serde::{Deserialize, Serialize};

/// Media stream session schema id.
pub const MEDIA_STREAM_SESSION_SCHEMA: &str = "rusty.quest.media_stream_session.v1";

/// Local diagnostic privacy tier.
pub const PRIVACY_LOCAL_LAN_DIAGNOSTIC: &str = "local_lan_diagnostic";

/// Encrypted relay privacy tier.
pub const PRIVACY_TRUSTED_RELAY_ENCRYPTED: &str = "trusted_relay_transport_encrypted";

/// Candidate tier for future end-to-end media encryption.
pub const PRIVACY_E2EE_CANDIDATE: &str = "untrusted_relay_end_to_end_encrypted_candidate";

/// Binary media payload plane token.
pub const PAYLOAD_PLANE_BINARY_MEDIA: &str = "binary-media";

/// H.264 codec token.
pub const VIDEO_CODEC_H264: &str = "h264";

/// Diagnostic H.264 stream framing token used by current reference adapters.
pub const STREAM_FRAMING_DIAGNOSTIC_H264: &str = "diagnostic-h264-packet-stream";

/// Existing RMANVID v4 framing for packed left/right stereo.
pub const STREAM_FRAMING_RMANVID_V4_PACKED_STEREO: &str = "rmanvid-v4-packed-stereo";

/// Sender source is already exposed as a local H.264 socket by another adapter.
pub const SOURCE_KIND_EXTERNAL_H264_SOCKET: &str = "external_h264_socket";

/// Sender source is captured through Camera2 and encoded by MediaCodec.
pub const SOURCE_KIND_CAMERA2_MEDIACODEC_SURFACE: &str = "camera2_mediacodec_surface";

/// Sender source is an app-consent display composite encoded by MediaCodec.
pub const SOURCE_KIND_DISPLAY_COMPOSITE_MEDIAPROJECTION_SURFACE: &str =
    "display_composite_mediaprojection_mediacodec_surface";

/// Sender source is a shell-sidecar display mirror encoded by MediaCodec.
pub const SOURCE_KIND_SHELL_DISPLAY_MIRROR_SURFACE: &str =
    "shell_display_mirror_mediacodec_surface";

/// Sender source is a synthetic diagnostic MediaCodec surface.
pub const SOURCE_KIND_DIAGNOSTIC_SYNTHETIC_SURFACE: &str =
    "diagnostic_synthetic_mediacodec_surface";

/// Capture authority for app-consent MediaProjection display capture.
pub const CAPTURE_AUTHORITY_ANDROID_MEDIAPROJECTION: &str = "android_mediaprojection_consent";

/// Capture authority for developer-only shell hidden API display capture.
pub const CAPTURE_AUTHORITY_ADB_SHELL_HIDDEN_API: &str = "adb_shell_hidden_api_developer_only";

/// Capture authority for Camera2 with Android permission evidence.
pub const CAPTURE_AUTHORITY_ANDROID_CAMERA_PERMISSION: &str = "android_camera_permission";

/// Capture authority for an already-owned external H.264 source.
pub const CAPTURE_AUTHORITY_EXTERNAL_H264_ADAPTER: &str = "external_h264_adapter";

/// Capture authority for a diagnostic synthetic producer.
pub const CAPTURE_AUTHORITY_DIAGNOSTIC_SYNTHETIC: &str = "diagnostic_synthetic_source";

/// Deployment classification for production-candidate app-consent routes.
pub const DEPLOYMENT_PRODUCTION_CANDIDATE: &str = "production_candidate";

/// Deployment classification for diagnostics that are not production routes.
pub const DEPLOYMENT_DIAGNOSTIC_ONLY: &str = "diagnostic_only";

/// Deployment classification for developer-only shell/lab routes.
pub const DEPLOYMENT_LAB_DEVELOPER_ONLY: &str = "lab_developer_only";

/// Camera permission is not required for this source.
pub const CAMERA_PERMISSION_NOT_REQUIRED: &str = "no_camera_permission_required";

/// Camera permission evidence is required for this source.
pub const CAMERA_PERMISSION_REQUIRED: &str = "camera_permission_required";

/// Media stream session plan.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamSessionPlan {
    /// Schema id.
    pub schema: String,
    /// Stable session id.
    pub session_id: String,
    /// Topology id such as `quest_display_to_pc`.
    pub topology_id: String,
    /// Privacy tier.
    pub privacy_tier: String,
    /// Participating devices or relays.
    pub devices: Vec<MediaStreamDevice>,
    /// Media sources available to lanes.
    pub sources: Vec<MediaStreamSource>,
    /// Media lanes in deterministic order.
    pub lanes: Vec<MediaStreamLane>,
    /// Per-device runtime adapter endpoint settings.
    #[serde(default)]
    pub runtime_endpoints: Vec<MediaStreamRuntimeEndpoint>,
    /// Per-lane outgoing peer or relay routes.
    #[serde(default)]
    pub transport_routes: Vec<MediaStreamTransportRoute>,
    /// Security policy for operator-visible streaming.
    pub security: MediaStreamSecurityPolicy,
    /// Required observability for session promotion.
    pub observability: MediaStreamObservabilityPolicy,
}

/// Media stream endpoint device.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamDevice {
    /// Stable device id in this plan.
    pub device_id: String,
    /// Device kind: `quest`, `android_phone`, `windows_pc`, or `relay`.
    pub device_kind: String,
    /// Role in the plan.
    pub role: String,
}

/// One media source that can feed an encoded lane.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamSource {
    /// Stable source id.
    pub source_id: String,
    /// Device id from `devices`.
    pub device_id: String,
    /// Broad source family, such as `quest-display-composite-mediaprojection`.
    pub source_family: String,
    /// Concrete source producer kind.
    pub source_kind: String,
    /// Operator-facing capture route label.
    pub capture_route: String,
    /// Permission or runtime authority used to obtain frames.
    pub capture_authority: String,
    /// Deployment classification for this source route.
    pub deployment_classification: String,
    /// Track role produced by this source.
    pub track_role: String,
    /// Whether the source requires a shell-launched developer sidecar.
    #[serde(default)]
    pub developer_shell_required: bool,
    /// Whether Android/user capture consent is required.
    #[serde(default)]
    pub consent_required: bool,
    /// Display capture metadata for display-derived sources.
    #[serde(default)]
    pub display: Option<DisplayCaptureDescriptor>,
    /// Camera capture metadata for Camera2-derived sources.
    #[serde(default)]
    pub camera: Option<CameraCaptureDescriptor>,
}

/// Display capture metadata that travels on the low-rate plan.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DisplayCaptureDescriptor {
    /// Display id or stable selection token.
    pub display_id: String,
    /// Rotation policy or runtime-reported rotation label.
    pub rotation: String,
    /// Density in dots per inch.
    pub density_dpi: u32,
    /// Content crop used for the encoded frame.
    pub content_crop: DisplayContentCrop,
    /// Protected-content policy for this route.
    pub protected_content_policy: String,
    /// Consent state expected for this display source.
    pub consent_state: String,
    /// Privacy indicator expectation for this display source.
    pub privacy_indicator: String,
    /// Foreground package reporting policy.
    pub foreground_package_reporting: String,
}

/// Pixel crop rectangle for display capture.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DisplayContentCrop {
    /// Left pixel offset.
    pub left: u32,
    /// Top pixel offset.
    pub top: u32,
    /// Crop width in pixels.
    pub width: u32,
    /// Crop height in pixels.
    pub height: u32,
}

/// Camera capture metadata that travels on the low-rate plan.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CameraCaptureDescriptor {
    /// Camera2 id.
    pub camera_id: String,
    /// Explicit per-track camera bindings for multi-camera sources.
    #[serde(default)]
    pub camera_ids: Vec<CameraTrackBinding>,
    /// Requested camera facing label.
    pub camera_facing: String,
    /// Permission policy required by this camera source.
    pub permission_policy: String,
}

/// Camera id bound to one role in a multi-camera source.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CameraTrackBinding {
    /// Track role such as `left` or `right`.
    pub track_role: String,
    /// Camera2 id.
    pub camera_id: String,
}

/// One high-rate media lane.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamLane {
    /// Stable lane id.
    pub lane_id: String,
    /// Direction token: `outgoing`, `incoming`, or `bidirectional`.
    pub direction: String,
    /// Source id from `sources`.
    pub source_id: String,
    /// Source device id.
    pub source_device_id: String,
    /// Sink device id.
    pub sink_device_id: String,
    /// Media contract.
    pub media: MediaStreamMediaConfig,
    /// Transport contract.
    pub transport: MediaStreamTransportConfig,
    /// Queue/backpressure policy.
    pub queue: MediaStreamQueuePolicy,
    /// Whether the receiver must be armed before sender start.
    pub receiver_first_required: bool,
}

/// Media config for a lane.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamMediaConfig {
    /// Stable track id.
    pub track_id: String,
    /// Track role: `left`, `right`, `mono`, or `display`.
    pub track_role: String,
    /// Media track kind, usually `video`.
    pub track_kind: String,
    /// Codec token.
    pub codec: String,
    /// Stream framing token.
    pub stream_framing: String,
    /// Width in pixels.
    pub width: u32,
    /// Height in pixels.
    pub height: u32,
    /// Requested frame rate.
    pub frame_rate_hz: u32,
    /// Target bitrate.
    pub bitrate_bps: u32,
    /// Maximum encoded packet size the sink must accept.
    pub max_packet_bytes: u32,
    /// How source/projection/timing metadata travels.
    pub metadata_transport: String,
    /// Timestamp-domain declaration.
    pub timestamp_domain: String,
    /// Required payload plane. High-rate media must be `binary-media`.
    pub high_rate_payload_plane: String,
}

/// Transport config for a lane.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamTransportConfig {
    /// Transport kind such as `lan_tcp` or `relay_tls`.
    pub transport_kind: String,
    /// Whether this lane requires a relay.
    pub relay_required: bool,
    /// Whether transport encryption is required.
    pub encryption_required: bool,
    /// Optional relay session id.
    #[serde(default)]
    pub relay_session_id: Option<String>,
}

/// Low-rate local runtime endpoint configuration for one participating device.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamRuntimeEndpoint {
    /// Device id from `devices`.
    pub device_id: String,
    /// Adapter kind, such as `quest_manifold_broker_android` or `windows_hostess`.
    pub adapter_kind: String,
    /// Local source bindings for this endpoint.
    #[serde(default)]
    pub source_bindings: Vec<MediaStreamSourceBinding>,
    /// Host where this endpoint's receiver adapter listens for local consumers.
    pub receiver_bind_host: String,
    /// Local receiver ports keyed by track role.
    #[serde(default)]
    pub receiver_ports: Vec<MediaStreamPortBinding>,
    /// Host where this endpoint accepts peer or relay media transport ingress.
    pub transport_bind_host: String,
    /// Peer/relay ingress ports keyed by track role.
    #[serde(default)]
    pub transport_receive_ports: Vec<MediaStreamPortBinding>,
}

/// Local encoded-source socket binding.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamSourceBinding {
    /// Source id from `sources`.
    pub source_id: String,
    /// Track role served by this binding.
    pub track_role: String,
    /// Local source host.
    pub source_host: String,
    /// Local source port.
    pub source_port: u16,
}

/// One local TCP port binding for a media track role.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamPortBinding {
    /// Track role: `left`, `right`, `mono`, or `display`.
    pub track_role: String,
    /// TCP port number.
    pub port: u16,
}

/// One outgoing peer or relay transport route for a media lane.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamTransportRoute {
    /// Lane id from `lanes`.
    pub lane_id: String,
    /// Source device id. Must match the lane source.
    pub source_device_id: String,
    /// Sink device id. Must match the lane sink.
    pub sink_device_id: String,
    /// Track role. Must match the lane media role.
    pub track_role: String,
    /// Route kind: `direct_tcp_connect` or `relay_tls_client`.
    pub route_kind: String,
    /// Host to connect for this route.
    pub connect_host: String,
    /// TCP port to connect.
    pub connect_port: u16,
    /// Relay channel, if a relay route is used.
    #[serde(default)]
    pub relay_channel: Option<String>,
    /// Reference to a secret/token source. The token itself must not be placed
    /// in the session plan.
    #[serde(default)]
    pub relay_token_ref: Option<String>,
    /// Optional TLS server name for relay routes.
    #[serde(default)]
    pub tls_server_name: Option<String>,
}

/// Queue and slow-peer policy.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamQueuePolicy {
    /// Maximum buffered media packets.
    pub max_buffered_packets: u32,
    /// Maximum buffered media bytes.
    pub max_buffered_bytes: u32,
    /// Drop policy token.
    pub drop_policy: String,
    /// Whether slow peers should be closed rather than buffered forever.
    pub slow_peer_close: bool,
}

/// Security policy for media streaming.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamSecurityPolicy {
    /// Streaming state must be visible to the operator.
    pub visible_streaming_indicator: bool,
    /// Pairing must be explicit.
    pub explicit_pairing_required: bool,
    /// Immediate stop command id.
    pub immediate_stop_command: String,
    /// Raw media payload logging is allowed only for local diagnostics.
    pub raw_media_logging: bool,
}

/// Observability policy for promotion gates.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamObservabilityPolicy {
    /// Required marker ids or marker phases.
    pub required_markers: Vec<String>,
    /// Required counter ids.
    pub required_counters: Vec<String>,
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
