//! Remote camera session data model and constants.

use serde::{Deserialize, Serialize};

pub use rusty_quest_device_link::{
    DIRECT_P2P_ROUTE_KIND as ROUTE_KIND_DIRECT_P2P_TCP,
    DIRECT_TCP_ROUTE_KIND as ROUTE_KIND_DIRECT_TCP_CONNECT,
    PLATFORM_DEFAULT_SOCKET_AUTHORITY as SOCKET_AUTHORITY_PLATFORM_DEFAULT,
    RUSTY_DIRECT_P2P_SOCKET_AUTHORITY as SOCKET_AUTHORITY_RUSTY_DIRECT_P2P,
};

/// Remote camera session schema id.
pub const REMOTE_CAMERA_SESSION_SCHEMA: &str = "rusty.quest.remote_camera_session.v1";

/// Local diagnostic privacy tier.
pub const PRIVACY_LOCAL_LAN_DIAGNOSTIC: &str = "local_lan_diagnostic";

/// Encrypted relay privacy tier.
pub const PRIVACY_TRUSTED_RELAY_ENCRYPTED: &str = "trusted_relay_transport_encrypted";

/// Candidate tier for future end-to-end media encryption.
pub const PRIVACY_E2EE_CANDIDATE: &str = "untrusted_relay_end_to_end_encrypted_candidate";

/// Binary media payload plane token.
pub const PAYLOAD_PLANE_BINARY_MEDIA: &str = rusty_quest_media_stream::PAYLOAD_PLANE_BINARY_MEDIA;

/// Existing independent left/right eye stream layout.
pub const MEDIA_LAYOUT_SEPARATE_EYE_STREAMS: &str = "separate-eye-streams";

/// Packed side-by-side layout with the left eye in the first half.
pub const MEDIA_LAYOUT_SIDE_BY_SIDE_LEFT_RIGHT: &str = "side-by-side-left-right";

/// Versioned packed-frame layout contract.
pub const PACKED_FRAME_LAYOUT_SCHEMA: &str = "rusty.quest.remote_camera.frame_layout.v1";

/// Packed side-by-side frame-layout kind.
pub const FRAME_LAYOUT_SIDE_BY_SIDE_LEFT_RIGHT: &str = "side_by_side_left_right";

/// Camera2 sensor timestamps are the physical source-pairing authority.
pub const PAIR_TIMESTAMP_CAMERA2_SENSOR: &str = "camera2_sensor_timestamp";

/// Bounded nearest-timestamp pairing policy.
pub const PAIRING_POLICY_NEAREST_TIMESTAMP_BOUNDED: &str = "nearest_timestamp_bounded";

/// Encrypted relay client route.
pub const ROUTE_KIND_RELAY_TLS_CLIENT: &str = "relay_tls_client";

/// H.264 codec token.
pub const VIDEO_CODEC_H264: &str = rusty_quest_media_stream::VIDEO_CODEC_H264;

/// Diagnostic H.264 stream framing token used by current reference adapters.
pub const STREAM_FRAMING_DIAGNOSTIC_H264: &str =
    rusty_quest_media_stream::STREAM_FRAMING_DIAGNOSTIC_H264;

/// Sender source is already exposed as a local H.264 socket by another adapter.
pub const SENDER_SOURCE_EXTERNAL_H264_SOCKET: &str =
    rusty_quest_media_stream::SOURCE_KIND_EXTERNAL_H264_SOCKET;

/// Sender source is captured through Camera2 and encoded by MediaCodec.
pub const SENDER_SOURCE_CAMERA2_MEDIACODEC_SURFACE: &str =
    rusty_quest_media_stream::SOURCE_KIND_CAMERA2_MEDIACODEC_SURFACE;

/// Sender source is a synthetic diagnostic MediaCodec surface.
pub const SENDER_SOURCE_DIAGNOSTIC_SYNTHETIC_SURFACE: &str =
    rusty_quest_media_stream::SOURCE_KIND_DIAGNOSTIC_SYNTHETIC_SURFACE;

/// Quest outside stereo left Camera2 id used by the reference Rusty-XR gates.
pub const QUEST_OUTSIDE_LEFT_CAMERA_ID: &str = "50";

/// Quest outside stereo right Camera2 id used by the reference Rusty-XR gates.
pub const QUEST_OUTSIDE_RIGHT_CAMERA_ID: &str = "51";

/// Sender source does not require camera permissions.
pub const CAMERA_PERMISSION_NOT_REQUIRED: &str =
    rusty_quest_media_stream::CAMERA_PERMISSION_NOT_REQUIRED;

/// Sender source requires manifest and runtime camera permission evidence.
pub const CAMERA_PERMISSION_REQUIRED: &str = rusty_quest_media_stream::CAMERA_PERMISSION_REQUIRED;

pub(crate) const PROP_ENABLED: &str = "debug.rustyquest.remote_camera.enabled";
pub(crate) const PROP_SESSION_ID: &str = "debug.rustyquest.remote_camera.session_id";
pub(crate) const PROP_TOPOLOGY_ID: &str = "debug.rustyquest.remote_camera.topology_id";
pub(crate) const PROP_ENDPOINT_DEVICE_ID: &str =
    "debug.rustyquest.remote_camera.endpoint_device_id";
pub(crate) const PROP_ENDPOINT_DEVICE_KIND: &str =
    "debug.rustyquest.remote_camera.endpoint_device_kind";
pub(crate) const PROP_ENDPOINT_ROLE: &str = "debug.rustyquest.remote_camera.endpoint_role";
pub(crate) const PROP_PRIVACY_TIER: &str = "debug.rustyquest.remote_camera.privacy_tier";
pub(crate) const PROP_LANE_COUNT: &str = "debug.rustyquest.remote_camera.lane_count";
pub(crate) const PROP_INCOMING_LANE_COUNT: &str =
    "debug.rustyquest.remote_camera.incoming_lane_count";
pub(crate) const PROP_OUTGOING_LANE_COUNT: &str =
    "debug.rustyquest.remote_camera.outgoing_lane_count";
pub(crate) const PROP_TRANSPORT_KIND: &str = "debug.rustyquest.remote_camera.transport_kind";
pub(crate) const PROP_ADAPTER_KIND: &str = "debug.rustyquest.remote_camera.adapter_kind";
pub(crate) const PROP_SENDER_SOURCE_KIND: &str =
    "debug.rustyquest.remote_camera.sender_source_kind";
pub(crate) const PROP_SENDER_SOURCE_HOST: &str =
    "debug.rustyquest.remote_camera.sender_source_host";
pub(crate) const PROP_SENDER_SOURCE_PORTS: &str =
    "debug.rustyquest.remote_camera.sender_source_ports";
pub(crate) const PROP_SENDER_MEDIA_PROFILES: &str =
    "debug.rustyquest.remote_camera.sender_media_profiles";
pub(crate) const PROP_SENDER_CAMERA_ID: &str = "debug.rustyquest.remote_camera.sender_camera_id";
pub(crate) const PROP_SENDER_CAMERA_IDS: &str = "debug.rustyquest.remote_camera.sender_camera_ids";
pub(crate) const PROP_SENDER_CAMERA_FACING: &str =
    "debug.rustyquest.remote_camera.sender_camera_facing";
pub(crate) const PROP_SENDER_QUALITY_PROFILE: &str =
    "debug.rustyquest.remote_camera.sender_quality_profile";
pub(crate) const PROP_CAMERA_PERMISSION_POLICY: &str =
    "debug.rustyquest.remote_camera.camera_permission_policy";
pub(crate) const PROP_RECEIVER_BIND_HOST: &str =
    "debug.rustyquest.remote_camera.receiver_bind_host";
pub(crate) const PROP_RECEIVER_PORTS: &str = "debug.rustyquest.remote_camera.receiver_ports";
pub(crate) const PROP_TRANSPORT_BIND_HOST: &str =
    "debug.rustyquest.remote_camera.transport_bind_host";
pub(crate) const PROP_TRANSPORT_RECEIVE_PORTS: &str =
    "debug.rustyquest.remote_camera.transport_receive_ports";
pub(crate) const PROP_TRANSPORT_ROUTES: &str = "debug.rustyquest.remote_camera.transport_routes";
pub(crate) const PROP_TRANSPORT_BIND_LOCAL_ADDRESS: &str =
    "debug.rustyquest.remote_camera.transport_bind_local_address";
pub(crate) const PROP_TRANSPORT_SOCKET_AUTHORITY: &str =
    "debug.rustyquest.remote_camera.transport_socket_authority";
pub(crate) const PROP_MEDIA_LAYOUT: &str = "debug.rustyquest.remote_camera.media_layout";
pub(crate) const PROP_SENDER_FRAME_LAYOUT: &str =
    "debug.rustyquest.remote_camera.sender_frame_layout";

/// Remote camera session plan.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RemoteCameraSessionPlan {
    /// Schema id.
    pub schema: String,
    /// Stable session id.
    pub session_id: String,
    /// Topology id such as `quest_to_quest_two_way` or `quest_android_phone_duplex`.
    pub topology_id: String,
    /// Privacy tier.
    pub privacy_tier: String,
    /// Explicit media layout. Existing plans deserialize as
    /// `separate-eye-streams`; packed stereo is never enabled implicitly.
    #[serde(default = "default_media_layout")]
    pub media_layout: String,
    /// Participating devices or relays.
    pub devices: Vec<RemoteCameraDevice>,
    /// Media lanes in deterministic order.
    pub lanes: Vec<RemoteCameraLane>,
    /// Per-device runtime adapter endpoint settings. These are low-rate local
    /// socket bindings and adapter identities, never media payload bytes.
    #[serde(default)]
    pub runtime_endpoints: Vec<RemoteCameraRuntimeEndpoint>,
    /// Per-lane outgoing peer/relay routes. These are low-rate connection
    /// plans from a local sender source to a peer or relay transport ingress.
    #[serde(default)]
    pub transport_routes: Vec<RemoteCameraTransportRoute>,
    /// Security policy for operator-visible remote streaming.
    pub security: RemoteCameraSecurityPolicy,
    /// Required observability for session promotion.
    pub observability: RemoteCameraObservabilityPolicy,
}

/// Remote camera endpoint device.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RemoteCameraDevice {
    /// Stable device id in this plan.
    pub device_id: String,
    /// Device kind: `quest`, `android_phone`, or `relay`.
    pub device_kind: String,
    /// Role in the plan.
    pub role: String,
}

/// One high-rate media lane.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RemoteCameraLane {
    /// Stable lane id.
    pub lane_id: String,
    /// Direction token: `outgoing`, `incoming`, or `bidirectional`.
    pub direction: String,
    /// Source device id.
    pub source_device_id: String,
    /// Sink device id.
    pub sink_device_id: String,
    /// Source family such as `quest-camera2` or `android-phone-camera2`.
    pub source_family: String,
    /// Media contract.
    pub media: RemoteCameraMediaConfig,
    /// Transport contract.
    pub transport: RemoteCameraTransportConfig,
    /// Queue/backpressure policy.
    pub queue: RemoteCameraQueuePolicy,
    /// Whether the receiver must be armed before sender start.
    pub receiver_first_required: bool,
}

/// Media config for a lane.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RemoteCameraMediaConfig {
    /// Stable track id.
    pub track_id: String,
    /// Eye/layout role: `left`, `right`, or `mono`.
    pub eye: String,
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
    /// How projection/timing metadata travels.
    pub metadata_transport: String,
    /// Timestamp-domain declaration.
    pub timestamp_domain: String,
    /// Required payload plane. High-rate media must be `binary-media`.
    pub high_rate_payload_plane: String,
    /// Optional validated packed-frame layout. This must be absent for the
    /// existing separate-eye lane model and present for a `stereo` lane.
    #[serde(default)]
    pub frame_layout: Option<RemoteCameraFrameLayout>,
}

/// One validated packed-frame layout and source-pairing policy.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RemoteCameraFrameLayout {
    /// Layout schema id.
    pub schema: String,
    /// Packed layout kind.
    pub kind: String,
    /// Eye order in the packed raster.
    pub eye_order: Vec<String>,
    /// Packed raster width.
    pub packed_width: u32,
    /// Packed raster height.
    pub packed_height: u32,
    /// Width of one eye region.
    pub per_eye_width: u32,
    /// Height of one eye region.
    pub per_eye_height: u32,
    /// Physical source timestamp authority.
    pub pair_timestamp_authority: String,
    /// Pair selection policy.
    pub pairing_policy: String,
    /// Maximum accepted absolute source-timestamp skew.
    pub max_pair_delta_ns: u64,
    /// Promotion mode must not reuse a stale eye.
    pub stale_eye_reuse_allowed: bool,
    /// Promotion mode requires a GPU-only compositor path.
    pub cpu_pixel_copy: bool,
}

/// Transport config for a lane.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RemoteCameraTransportConfig {
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
pub struct RemoteCameraRuntimeEndpoint {
    /// Device id from `devices`.
    pub device_id: String,
    /// Adapter kind, such as `quest_manifold_broker_android` or
    /// `android_companion`.
    pub adapter_kind: String,
    /// Sender source kind. `external_h264_socket` means another local adapter
    /// already exposes H.264 bytes; `camera2_mediacodec_surface` means this
    /// endpoint captures Camera2 into a MediaCodec input surface.
    #[serde(default = "default_sender_source_kind")]
    pub sender_source_kind: String,
    /// Host where this endpoint's sender adapter exposes local H.264 source
    /// streams for relay/direct transport.
    pub sender_source_host: String,
    /// Local sender source ports keyed by eye/layout role.
    pub sender_source_ports: Vec<RemoteCameraPortBinding>,
    /// Optional Camera2 camera id override for camera-owned sender sources.
    #[serde(default)]
    pub sender_camera_id: Option<String>,
    /// Optional per-eye Camera2 camera id bindings for stereo sender sources.
    #[serde(default)]
    pub sender_camera_ids: Vec<RemoteCameraCameraBinding>,
    /// Optional requested camera facing for camera-owned sender sources.
    #[serde(default)]
    pub sender_camera_facing: Option<String>,
    /// Optional camera quality profile token for camera-owned sender sources.
    #[serde(default)]
    pub sender_quality_profile: Option<String>,
    /// Camera permission policy required by this endpoint source.
    #[serde(default = "default_camera_permission_policy")]
    pub camera_permission_policy: String,
    /// Host where this endpoint's receiver adapter listens for local app
    /// consumers, such as the Makepad external H.264 player.
    pub receiver_bind_host: String,
    /// Local receiver ports keyed by eye/layout role.
    pub receiver_ports: Vec<RemoteCameraPortBinding>,
    /// Host where this endpoint accepts peer or relay media transport ingress.
    pub transport_bind_host: String,
    /// Peer/relay ingress ports keyed by eye/layout role. Receiver adapters
    /// bridge these binary streams into the local receiver ports above.
    pub transport_receive_ports: Vec<RemoteCameraPortBinding>,
}

/// One local TCP port binding for a media eye/layout role.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RemoteCameraPortBinding {
    /// Eye/layout role: `left`, `right`, or `mono`.
    pub eye: String,
    /// TCP port number.
    pub port: u16,
}

/// One camera id binding for a media eye/layout role.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RemoteCameraCameraBinding {
    /// Eye/layout role: `left`, `right`, or `mono`.
    pub eye: String,
    /// Camera2 camera id, for example Quest outside stereo ids `50` and `51`.
    pub camera_id: String,
}

/// One outgoing peer or relay transport route for a media lane.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RemoteCameraTransportRoute {
    /// Lane id from `lanes`.
    pub lane_id: String,
    /// Source device id. Must match the lane source.
    pub source_device_id: String,
    /// Sink device id. Must match the lane sink.
    pub sink_device_id: String,
    /// Eye/layout role. Must match the lane media eye.
    pub eye: String,
    /// Route kind: `direct_tcp_connect`, `direct_p2p_tcp`, or `relay_tls_client`.
    pub route_kind: String,
    /// Host to connect for this route. For direct LAN this is the peer ingress
    /// host; for relay routes this is the relay host.
    pub connect_host: String,
    /// TCP port to connect.
    pub connect_port: u16,
    /// Optional socket authority. `direct_p2p_tcp` requires
    /// `rusty_direct_p2p_socket_authority`.
    #[serde(default)]
    pub socket_authority: Option<String>,
    /// Optional source address to bind before connecting. Direct P2P routes
    /// require a concrete IPv4 address on the peer subnet.
    #[serde(default)]
    pub local_bind_host: Option<String>,
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
pub struct RemoteCameraQueuePolicy {
    /// Maximum buffered media packets.
    pub max_buffered_packets: u32,
    /// Maximum buffered media bytes.
    pub max_buffered_bytes: u32,
    /// Drop policy token.
    pub drop_policy: String,
    /// Whether slow peers should be closed rather than buffered forever.
    pub slow_peer_close: bool,
}

/// Security policy for remote streaming.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RemoteCameraSecurityPolicy {
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
pub struct RemoteCameraObservabilityPolicy {
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

fn default_sender_source_kind() -> String {
    SENDER_SOURCE_EXTERNAL_H264_SOCKET.to_string()
}

fn default_media_layout() -> String {
    MEDIA_LAYOUT_SEPARATE_EYE_STREAMS.to_string()
}

fn default_camera_permission_policy() -> String {
    CAMERA_PERMISSION_NOT_REQUIRED.to_string()
}
