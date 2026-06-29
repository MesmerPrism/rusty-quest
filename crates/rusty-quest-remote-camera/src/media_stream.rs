//! Compatibility mapping from remote-camera plans to generic media streams.

use std::collections::BTreeMap;

use rusty_quest_media_stream as media;

use crate::model::*;
use crate::validation::validate_remote_camera_session;

/// Maximum packet size accepted by the current diagnostic H.264 compatibility path.
pub const MEDIA_STREAM_COMPAT_MAX_PACKET_BYTES: u32 = 1_048_576;

/// Build a source-neutral media stream plan from a validated remote-camera plan.
///
/// This preserves the existing remote-camera JSON contract while giving newer
/// display/camera streaming code one generic media vocabulary to consume. The
/// generated plan is low-rate only; it contains no frame payloads and does not
/// open sockets, cameras, encoders, or transports.
pub fn build_media_stream_session_plan(
    plan: &RemoteCameraSessionPlan,
) -> Result<media::MediaStreamSessionPlan, Vec<ValidationError>> {
    validate_remote_camera_session(plan)?;

    let runtime_endpoints_by_device = plan
        .runtime_endpoints
        .iter()
        .map(|endpoint| (endpoint.device_id.as_str(), endpoint))
        .collect::<BTreeMap<_, _>>();
    let mut source_ids_by_device_role = BTreeMap::new();
    let mut sources = Vec::new();

    for lane in &plan.lanes {
        let key = (
            lane.source_device_id.clone(),
            lane.media.eye.clone(),
            lane.source_family.clone(),
        );
        if source_ids_by_device_role.contains_key(&key) {
            continue;
        }
        let source_id = format!(
            "{}.{}.remote_camera_source",
            lane.source_device_id, lane.media.eye
        );
        let Some(endpoint) = runtime_endpoints_by_device.get(lane.source_device_id.as_str()) else {
            continue;
        };
        sources.push(build_source(&source_id, lane, endpoint));
        source_ids_by_device_role.insert(key, source_id);
    }

    let media_plan = media::MediaStreamSessionPlan {
        schema: media::MEDIA_STREAM_SESSION_SCHEMA.to_string(),
        session_id: format!("{}.media_stream_compat", plan.session_id),
        topology_id: plan.topology_id.clone(),
        privacy_tier: plan.privacy_tier.clone(),
        devices: plan
            .devices
            .iter()
            .map(|device| media::MediaStreamDevice {
                device_id: device.device_id.clone(),
                device_kind: device.device_kind.clone(),
                role: device.role.clone(),
            })
            .collect(),
        sources,
        lanes: plan
            .lanes
            .iter()
            .map(|lane| {
                let source_id = source_ids_by_device_role
                    .get(&(
                        lane.source_device_id.clone(),
                        lane.media.eye.clone(),
                        lane.source_family.clone(),
                    ))
                    .cloned()
                    .unwrap_or_else(|| {
                        format!(
                            "{}.{}.remote_camera_source",
                            lane.source_device_id, lane.media.eye
                        )
                    });
                media::MediaStreamLane {
                    lane_id: lane.lane_id.clone(),
                    direction: lane.direction.clone(),
                    source_id,
                    source_device_id: lane.source_device_id.clone(),
                    sink_device_id: lane.sink_device_id.clone(),
                    media: media::MediaStreamMediaConfig {
                        track_id: lane.media.track_id.clone(),
                        track_role: lane.media.eye.clone(),
                        track_kind: lane.media.track_kind.clone(),
                        codec: lane.media.codec.clone(),
                        stream_framing: lane.media.stream_framing.clone(),
                        width: lane.media.width,
                        height: lane.media.height,
                        frame_rate_hz: lane.media.frame_rate_hz,
                        bitrate_bps: lane.media.bitrate_bps,
                        max_packet_bytes: MEDIA_STREAM_COMPAT_MAX_PACKET_BYTES,
                        metadata_transport: lane.media.metadata_transport.clone(),
                        timestamp_domain: lane.media.timestamp_domain.clone(),
                        high_rate_payload_plane: lane.media.high_rate_payload_plane.clone(),
                    },
                    transport: media::MediaStreamTransportConfig {
                        transport_kind: lane.transport.transport_kind.clone(),
                        relay_required: lane.transport.relay_required,
                        encryption_required: lane.transport.encryption_required,
                        relay_session_id: lane.transport.relay_session_id.clone(),
                    },
                    queue: media::MediaStreamQueuePolicy {
                        max_buffered_packets: lane.queue.max_buffered_packets,
                        max_buffered_bytes: lane.queue.max_buffered_bytes,
                        drop_policy: lane.queue.drop_policy.clone(),
                        slow_peer_close: lane.queue.slow_peer_close,
                    },
                    receiver_first_required: lane.receiver_first_required,
                }
            })
            .collect(),
        runtime_endpoints: plan
            .runtime_endpoints
            .iter()
            .map(|endpoint| build_runtime_endpoint(endpoint, &source_ids_by_device_role))
            .collect(),
        transport_routes: plan
            .transport_routes
            .iter()
            .map(|route| media::MediaStreamTransportRoute {
                lane_id: route.lane_id.clone(),
                source_device_id: route.source_device_id.clone(),
                sink_device_id: route.sink_device_id.clone(),
                track_role: route.eye.clone(),
                route_kind: route.route_kind.clone(),
                connect_host: route.connect_host.clone(),
                connect_port: route.connect_port,
                relay_channel: route.relay_channel.clone(),
                relay_token_ref: route.relay_token_ref.clone(),
                tls_server_name: route.tls_server_name.clone(),
            })
            .collect(),
        security: media::MediaStreamSecurityPolicy {
            visible_streaming_indicator: plan.security.visible_streaming_indicator,
            explicit_pairing_required: plan.security.explicit_pairing_required,
            immediate_stop_command: plan.security.immediate_stop_command.clone(),
            raw_media_logging: plan.security.raw_media_logging,
        },
        observability: build_observability(&plan.observability),
    };

    media::validate_media_stream_session(&media_plan).map_err(|errors| {
        errors
            .into_iter()
            .map(|error| {
                ValidationError::new(format!("media stream compatibility: {}", error.message))
            })
            .collect::<Vec<_>>()
    })?;
    Ok(media_plan)
}

fn build_source(
    source_id: &str,
    lane: &RemoteCameraLane,
    endpoint: &RemoteCameraRuntimeEndpoint,
) -> media::MediaStreamSource {
    let source_kind = map_source_kind(&endpoint.sender_source_kind).to_string();
    let camera = if source_kind == media::SOURCE_KIND_CAMERA2_MEDIACODEC_SURFACE {
        Some(media::CameraCaptureDescriptor {
            camera_id: camera_id_for_eye(endpoint, &lane.media.eye),
            camera_facing: endpoint
                .sender_camera_facing
                .clone()
                .unwrap_or_else(|| "unspecified".to_string()),
            permission_policy: endpoint.camera_permission_policy.clone(),
        })
    } else {
        None
    };

    media::MediaStreamSource {
        source_id: source_id.to_string(),
        device_id: lane.source_device_id.clone(),
        source_family: lane.source_family.clone(),
        source_kind: source_kind.clone(),
        capture_route: capture_route_for_source_kind(&source_kind).to_string(),
        capture_authority: capture_authority_for_source_kind(&source_kind).to_string(),
        deployment_classification: media::DEPLOYMENT_DIAGNOSTIC_ONLY.to_string(),
        track_role: lane.media.eye.clone(),
        developer_shell_required: false,
        consent_required: false,
        display: None,
        camera,
    }
}

fn build_runtime_endpoint(
    endpoint: &RemoteCameraRuntimeEndpoint,
    source_ids_by_device_role: &BTreeMap<(String, String, String), String>,
) -> media::MediaStreamRuntimeEndpoint {
    media::MediaStreamRuntimeEndpoint {
        device_id: endpoint.device_id.clone(),
        adapter_kind: endpoint.adapter_kind.clone(),
        source_bindings: endpoint
            .sender_source_ports
            .iter()
            .filter_map(|binding| {
                source_id_for_endpoint_role(endpoint, &binding.eye, source_ids_by_device_role).map(
                    |source_id| media::MediaStreamSourceBinding {
                        source_id,
                        track_role: binding.eye.clone(),
                        source_host: endpoint.sender_source_host.clone(),
                        source_port: binding.port,
                    },
                )
            })
            .collect(),
        receiver_bind_host: endpoint.receiver_bind_host.clone(),
        receiver_ports: endpoint
            .receiver_ports
            .iter()
            .map(|binding| media::MediaStreamPortBinding {
                track_role: binding.eye.clone(),
                port: binding.port,
            })
            .collect(),
        transport_bind_host: endpoint.transport_bind_host.clone(),
        transport_receive_ports: endpoint
            .transport_receive_ports
            .iter()
            .map(|binding| media::MediaStreamPortBinding {
                track_role: binding.eye.clone(),
                port: binding.port,
            })
            .collect(),
    }
}

fn build_observability(
    observability: &RemoteCameraObservabilityPolicy,
) -> media::MediaStreamObservabilityPolicy {
    let mut required_counters = observability.required_counters.clone();
    for required in ["capture_to_encode_ms", "encode_to_receive_ms"] {
        if !required_counters.iter().any(|counter| counter == required) {
            required_counters.push(required.to_string());
        }
    }
    media::MediaStreamObservabilityPolicy {
        required_markers: observability.required_markers.clone(),
        required_counters,
    }
}

fn source_id_for_endpoint_role(
    endpoint: &RemoteCameraRuntimeEndpoint,
    eye: &str,
    source_ids_by_device_role: &BTreeMap<(String, String, String), String>,
) -> Option<String> {
    source_ids_by_device_role
        .iter()
        .find_map(|((device_id, role, _source_family), source_id)| {
            (device_id == &endpoint.device_id && role == eye).then(|| source_id.clone())
        })
}

fn camera_id_for_eye(endpoint: &RemoteCameraRuntimeEndpoint, eye: &str) -> String {
    endpoint
        .sender_camera_ids
        .iter()
        .find(|binding| binding.eye == eye)
        .map(|binding| binding.camera_id.clone())
        .or_else(|| endpoint.sender_camera_id.clone())
        .unwrap_or_else(|| "default".to_string())
}

fn map_source_kind(source_kind: &str) -> &str {
    match source_kind {
        SENDER_SOURCE_EXTERNAL_H264_SOCKET => media::SOURCE_KIND_EXTERNAL_H264_SOCKET,
        SENDER_SOURCE_CAMERA2_MEDIACODEC_SURFACE => media::SOURCE_KIND_CAMERA2_MEDIACODEC_SURFACE,
        SENDER_SOURCE_DIAGNOSTIC_SYNTHETIC_SURFACE => {
            media::SOURCE_KIND_DIAGNOSTIC_SYNTHETIC_SURFACE
        }
        _ => source_kind,
    }
}

fn capture_route_for_source_kind(source_kind: &str) -> &'static str {
    match source_kind {
        media::SOURCE_KIND_EXTERNAL_H264_SOCKET => "external-h264-socket",
        media::SOURCE_KIND_CAMERA2_MEDIACODEC_SURFACE => "camera2-mediacodec-surface",
        media::SOURCE_KIND_DIAGNOSTIC_SYNTHETIC_SURFACE => {
            "diagnostic-synthetic-mediacodec-surface"
        }
        _ => "unknown-remote-camera-source",
    }
}

fn capture_authority_for_source_kind(source_kind: &str) -> &'static str {
    match source_kind {
        media::SOURCE_KIND_EXTERNAL_H264_SOCKET => media::CAPTURE_AUTHORITY_EXTERNAL_H264_ADAPTER,
        media::SOURCE_KIND_CAMERA2_MEDIACODEC_SURFACE => {
            media::CAPTURE_AUTHORITY_ANDROID_CAMERA_PERMISSION
        }
        media::SOURCE_KIND_DIAGNOSTIC_SYNTHETIC_SURFACE => {
            media::CAPTURE_AUTHORITY_DIAGNOSTIC_SYNTHETIC
        }
        _ => media::CAPTURE_AUTHORITY_EXTERNAL_H264_ADAPTER,
    }
}
