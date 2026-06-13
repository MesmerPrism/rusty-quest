//! Validation rules for remote camera session plans.

use std::collections::{BTreeMap, BTreeSet};

use crate::model::*;

/// Validate a remote camera session plan.
pub fn validate_remote_camera_session(
    plan: &RemoteCameraSessionPlan,
) -> Result<(), Vec<ValidationError>> {
    let mut errors = Vec::new();
    validate_required_text("schema", &plan.schema, &mut errors);
    validate_required_text("session_id", &plan.session_id, &mut errors);
    validate_required_text("topology_id", &plan.topology_id, &mut errors);
    if plan.schema != REMOTE_CAMERA_SESSION_SCHEMA {
        errors.push(ValidationError::new(format!(
            "unsupported remote camera session schema {}",
            plan.schema
        )));
    }
    validate_privacy_tier(plan, &mut errors);
    validate_security(&plan.security, &plan.privacy_tier, &mut errors);

    let devices = validate_devices(&plan.devices, &mut errors);
    validate_lanes(&plan.lanes, &devices, &mut errors);
    let runtime_endpoints =
        validate_runtime_endpoints(&plan.runtime_endpoints, &devices, &plan.lanes, &mut errors);
    validate_runtime_endpoint_coverage(&devices, &runtime_endpoints, &mut errors);
    validate_transport_routes(
        &plan.transport_routes,
        &plan.lanes,
        &runtime_endpoints,
        &mut errors,
    );
    validate_topology(plan, &devices, &mut errors);
    validate_observability(&plan.observability, &mut errors);

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

fn validate_devices<'a>(
    devices: &'a [RemoteCameraDevice],
    errors: &mut Vec<ValidationError>,
) -> BTreeMap<&'a str, &'a RemoteCameraDevice> {
    if devices.is_empty() {
        errors.push(ValidationError::new("devices must not be empty"));
    }
    let mut seen = BTreeMap::new();
    for device in devices {
        validate_required_text("device_id", &device.device_id, errors);
        validate_required_text("device_kind", &device.device_kind, errors);
        validate_required_text("role", &device.role, errors);
        if !matches!(
            device.device_kind.as_str(),
            "quest" | "android_phone" | "relay"
        ) {
            errors.push(ValidationError::new(format!(
                "unsupported device_kind {}",
                device.device_kind
            )));
        }
        if seen.insert(device.device_id.as_str(), device).is_some() {
            errors.push(ValidationError::new(format!(
                "duplicate device_id {}",
                device.device_id
            )));
        }
    }
    seen
}

fn validate_lanes(
    lanes: &[RemoteCameraLane],
    devices: &BTreeMap<&str, &RemoteCameraDevice>,
    errors: &mut Vec<ValidationError>,
) {
    if lanes.is_empty() {
        errors.push(ValidationError::new("lanes must not be empty"));
    }
    let mut lane_ids = BTreeSet::new();
    for lane in lanes {
        validate_required_text("lane_id", &lane.lane_id, errors);
        validate_required_text("direction", &lane.direction, errors);
        validate_required_text("source_device_id", &lane.source_device_id, errors);
        validate_required_text("sink_device_id", &lane.sink_device_id, errors);
        validate_required_text("source_family", &lane.source_family, errors);
        if !lane_ids.insert(lane.lane_id.as_str()) {
            errors.push(ValidationError::new(format!(
                "duplicate lane_id {}",
                lane.lane_id
            )));
        }
        if !matches!(
            lane.direction.as_str(),
            "outgoing" | "incoming" | "bidirectional"
        ) {
            errors.push(ValidationError::new(format!(
                "unsupported lane direction {}",
                lane.direction
            )));
        }
        if !devices.contains_key(lane.source_device_id.as_str()) {
            errors.push(ValidationError::new(format!(
                "lane {} references unknown source_device_id {}",
                lane.lane_id, lane.source_device_id
            )));
        }
        if !devices.contains_key(lane.sink_device_id.as_str()) {
            errors.push(ValidationError::new(format!(
                "lane {} references unknown sink_device_id {}",
                lane.lane_id, lane.sink_device_id
            )));
        }
        if lane.source_device_id == lane.sink_device_id {
            errors.push(ValidationError::new(format!(
                "lane {} source and sink must differ",
                lane.lane_id
            )));
        }
        validate_route_token("lane.lane_id", &lane.lane_id, errors);
        validate_route_token("lane.source_family", &lane.source_family, errors);
        if !lane.receiver_first_required {
            errors.push(ValidationError::new(format!(
                "lane {} must require receiver-first start",
                lane.lane_id
            )));
        }
        validate_media(&lane.lane_id, &lane.media, errors);
        validate_transport(&lane.lane_id, &lane.transport, errors);
        validate_queue(&lane.lane_id, &lane.queue, errors);
    }
}

fn validate_runtime_endpoints<'a>(
    runtime_endpoints: &'a [RemoteCameraRuntimeEndpoint],
    devices: &BTreeMap<&str, &RemoteCameraDevice>,
    lanes: &[RemoteCameraLane],
    errors: &mut Vec<ValidationError>,
) -> BTreeMap<&'a str, &'a RemoteCameraRuntimeEndpoint> {
    let mut seen = BTreeMap::new();
    for endpoint in runtime_endpoints {
        validate_required_text("runtime_endpoint.device_id", &endpoint.device_id, errors);
        validate_required_text(
            "runtime_endpoint.adapter_kind",
            &endpoint.adapter_kind,
            errors,
        );
        validate_required_text(
            "runtime_endpoint.sender_source_kind",
            &endpoint.sender_source_kind,
            errors,
        );
        validate_required_text(
            "runtime_endpoint.sender_source_host",
            &endpoint.sender_source_host,
            errors,
        );
        validate_required_text(
            "runtime_endpoint.camera_permission_policy",
            &endpoint.camera_permission_policy,
            errors,
        );
        validate_required_text(
            "runtime_endpoint.receiver_bind_host",
            &endpoint.receiver_bind_host,
            errors,
        );
        validate_required_text(
            "runtime_endpoint.transport_bind_host",
            &endpoint.transport_bind_host,
            errors,
        );
        if !matches!(
            endpoint.adapter_kind.as_str(),
            "quest_manifold_broker_android" | "android_companion"
        ) {
            errors.push(ValidationError::new(format!(
                "runtime endpoint {} has unsupported adapter_kind {}",
                endpoint.device_id, endpoint.adapter_kind
            )));
        }
        validate_sender_source_policy(endpoint, devices, lanes, errors);
        match devices.get(endpoint.device_id.as_str()) {
            Some(device) if device.device_kind == "relay" => {
                errors.push(ValidationError::new(format!(
                    "runtime endpoint {} must target a media endpoint, not a relay",
                    endpoint.device_id
                )));
            }
            Some(_) => {}
            None => errors.push(ValidationError::new(format!(
                "runtime endpoint references unknown device_id {}",
                endpoint.device_id
            ))),
        }
        if seen.insert(endpoint.device_id.as_str(), endpoint).is_some() {
            errors.push(ValidationError::new(format!(
                "duplicate runtime endpoint for device_id {}",
                endpoint.device_id
            )));
        }
        validate_port_bindings(
            &endpoint.device_id,
            "sender_source_ports",
            &endpoint.sender_source_ports,
            errors,
        );
        validate_port_bindings(
            &endpoint.device_id,
            "receiver_ports",
            &endpoint.receiver_ports,
            errors,
        );
        validate_port_bindings(
            &endpoint.device_id,
            "transport_receive_ports",
            &endpoint.transport_receive_ports,
            errors,
        );
    }
    seen
}

fn validate_sender_source_policy(
    endpoint: &RemoteCameraRuntimeEndpoint,
    devices: &BTreeMap<&str, &RemoteCameraDevice>,
    lanes: &[RemoteCameraLane],
    errors: &mut Vec<ValidationError>,
) {
    if !matches!(
        endpoint.sender_source_kind.as_str(),
        SENDER_SOURCE_EXTERNAL_H264_SOCKET
            | SENDER_SOURCE_CAMERA2_MEDIACODEC_SURFACE
            | SENDER_SOURCE_DIAGNOSTIC_SYNTHETIC_SURFACE
    ) {
        errors.push(ValidationError::new(format!(
            "runtime endpoint {} has unsupported sender_source_kind {}",
            endpoint.device_id, endpoint.sender_source_kind
        )));
    }
    if !matches!(
        endpoint.camera_permission_policy.as_str(),
        CAMERA_PERMISSION_NOT_REQUIRED | CAMERA_PERMISSION_REQUIRED
    ) {
        errors.push(ValidationError::new(format!(
            "runtime endpoint {} has unsupported camera_permission_policy {}",
            endpoint.device_id, endpoint.camera_permission_policy
        )));
    }
    validate_route_token(
        "runtime_endpoint.sender_source_kind",
        &endpoint.sender_source_kind,
        errors,
    );
    validate_route_token(
        "runtime_endpoint.camera_permission_policy",
        &endpoint.camera_permission_policy,
        errors,
    );
    if let Some(camera_id) = endpoint.sender_camera_id.as_deref() {
        validate_route_token("runtime_endpoint.sender_camera_id", camera_id, errors);
    }
    let camera_bindings =
        validate_camera_bindings(&endpoint.device_id, &endpoint.sender_camera_ids, errors);
    if let Some(camera_facing) = endpoint.sender_camera_facing.as_deref() {
        validate_route_token(
            "runtime_endpoint.sender_camera_facing",
            camera_facing,
            errors,
        );
    }
    if let Some(quality_profile) = endpoint.sender_quality_profile.as_deref() {
        validate_route_token(
            "runtime_endpoint.sender_quality_profile",
            quality_profile,
            errors,
        );
    }
    if endpoint.sender_source_kind == SENDER_SOURCE_CAMERA2_MEDIACODEC_SURFACE
        && endpoint.camera_permission_policy != CAMERA_PERMISSION_REQUIRED
    {
        errors.push(ValidationError::new(format!(
            "runtime endpoint {} camera2_mediacodec_surface requires camera_permission_required",
            endpoint.device_id
        )));
    }
    if endpoint.sender_source_kind != SENDER_SOURCE_CAMERA2_MEDIACODEC_SURFACE
        && endpoint.camera_permission_policy == CAMERA_PERMISSION_REQUIRED
    {
        errors.push(ValidationError::new(format!(
            "runtime endpoint {} camera permissions are allowed only for camera2_mediacodec_surface",
            endpoint.device_id
        )));
    }
    let Some(device) = devices.get(endpoint.device_id.as_str()) else {
        return;
    };
    let outgoing_lanes = lanes
        .iter()
        .filter(|lane| lane.source_device_id == endpoint.device_id)
        .collect::<Vec<_>>();
    if endpoint.sender_source_kind == SENDER_SOURCE_CAMERA2_MEDIACODEC_SURFACE {
        if !matches!(device.device_kind.as_str(), "quest" | "android_phone") {
            errors.push(ValidationError::new(format!(
                "runtime endpoint {} camera2_mediacodec_surface requires a camera-capable endpoint",
                endpoint.device_id
            )));
        }
        for lane in &outgoing_lanes {
            if !matches!(
                lane.source_family.as_str(),
                "quest-camera2" | "android-phone-camera2"
            ) {
                errors.push(ValidationError::new(format!(
                    "lane {} camera2 sender endpoint requires a camera2 source_family",
                    lane.lane_id
                )));
            }
            validate_route_token("lane.source_family", &lane.source_family, errors);
            validate_route_token("media.stream_framing", &lane.media.stream_framing, errors);
        }
        if device.device_kind == "quest" {
            let outgoing_eyes = outgoing_lanes
                .iter()
                .map(|lane| lane.media.eye.as_str())
                .collect::<BTreeSet<_>>();
            for eye in outgoing_eyes {
                if matches!(eye, "left" | "right") && !camera_bindings.contains_key(eye) {
                    errors.push(ValidationError::new(format!(
                        "runtime endpoint {} quest camera2 sender requires sender_camera_ids binding for {}",
                        endpoint.device_id, eye
                    )));
                }
            }
            if let (Some(left), Some(right)) =
                (camera_bindings.get("left"), camera_bindings.get("right"))
            {
                if left == right {
                    errors.push(ValidationError::new(format!(
                        "runtime endpoint {} quest left/right sender_camera_ids must differ",
                        endpoint.device_id
                    )));
                }
                if *left != QUEST_OUTSIDE_LEFT_CAMERA_ID || *right != QUEST_OUTSIDE_RIGHT_CAMERA_ID
                {
                    errors.push(ValidationError::new(format!(
                        "runtime endpoint {} quest outside stereo camera ids must be left={}, right={}",
                        endpoint.device_id,
                        QUEST_OUTSIDE_LEFT_CAMERA_ID,
                        QUEST_OUTSIDE_RIGHT_CAMERA_ID
                    )));
                }
            }
        }
    }
}

fn validate_runtime_endpoint_coverage(
    devices: &BTreeMap<&str, &RemoteCameraDevice>,
    runtime_endpoints: &BTreeMap<&str, &RemoteCameraRuntimeEndpoint>,
    errors: &mut Vec<ValidationError>,
) {
    for device in devices.values() {
        if device.device_kind != "relay"
            && !runtime_endpoints.contains_key(device.device_id.as_str())
        {
            errors.push(ValidationError::new(format!(
                "device {} requires a runtime endpoint",
                device.device_id
            )));
        }
    }
}

fn validate_port_bindings(
    device_id: &str,
    field: &str,
    bindings: &[RemoteCameraPortBinding],
    errors: &mut Vec<ValidationError>,
) {
    if bindings.is_empty() {
        errors.push(ValidationError::new(format!(
            "runtime endpoint {device_id} {field} must not be empty"
        )));
    }
    let mut seen_eyes = BTreeSet::new();
    let mut seen_ports = BTreeSet::new();
    for binding in bindings {
        validate_required_text("runtime_endpoint.port.eye", &binding.eye, errors);
        if !matches!(binding.eye.as_str(), "left" | "right" | "mono") {
            errors.push(ValidationError::new(format!(
                "runtime endpoint {device_id} {field} has unsupported eye {}",
                binding.eye
            )));
        }
        if binding.port == 0 {
            errors.push(ValidationError::new(format!(
                "runtime endpoint {device_id} {field} port for {} must be nonzero",
                binding.eye
            )));
        }
        if !seen_eyes.insert(binding.eye.as_str()) {
            errors.push(ValidationError::new(format!(
                "runtime endpoint {device_id} {field} repeats eye {}",
                binding.eye
            )));
        }
        if !seen_ports.insert(binding.port) {
            errors.push(ValidationError::new(format!(
                "runtime endpoint {device_id} {field} repeats port {}",
                binding.port
            )));
        }
    }
}

fn validate_camera_bindings<'a>(
    device_id: &str,
    bindings: &'a [RemoteCameraCameraBinding],
    errors: &mut Vec<ValidationError>,
) -> BTreeMap<&'a str, &'a str> {
    let mut seen = BTreeMap::new();
    for binding in bindings {
        validate_required_text("runtime_endpoint.camera.eye", &binding.eye, errors);
        validate_required_text(
            "runtime_endpoint.camera.camera_id",
            &binding.camera_id,
            errors,
        );
        validate_route_token("runtime_endpoint.camera.eye", &binding.eye, errors);
        validate_route_token(
            "runtime_endpoint.camera.camera_id",
            &binding.camera_id,
            errors,
        );
        if !matches!(binding.eye.as_str(), "left" | "right" | "mono") {
            errors.push(ValidationError::new(format!(
                "runtime endpoint {device_id} sender_camera_ids has unsupported eye {}",
                binding.eye
            )));
        }
        if seen
            .insert(binding.eye.as_str(), binding.camera_id.as_str())
            .is_some()
        {
            errors.push(ValidationError::new(format!(
                "runtime endpoint {device_id} sender_camera_ids repeats eye {}",
                binding.eye
            )));
        }
    }
    seen
}

fn validate_transport_routes(
    routes: &[RemoteCameraTransportRoute],
    lanes: &[RemoteCameraLane],
    runtime_endpoints: &BTreeMap<&str, &RemoteCameraRuntimeEndpoint>,
    errors: &mut Vec<ValidationError>,
) {
    if routes.is_empty() && !lanes.is_empty() {
        errors.push(ValidationError::new("transport_routes must not be empty"));
    }
    let lanes_by_id = lanes
        .iter()
        .map(|lane| (lane.lane_id.as_str(), lane))
        .collect::<BTreeMap<_, _>>();
    let mut seen = BTreeSet::new();
    for route in routes {
        validate_required_text("transport_route.lane_id", &route.lane_id, errors);
        validate_required_text(
            "transport_route.source_device_id",
            &route.source_device_id,
            errors,
        );
        validate_required_text(
            "transport_route.sink_device_id",
            &route.sink_device_id,
            errors,
        );
        validate_required_text("transport_route.eye", &route.eye, errors);
        validate_required_text("transport_route.route_kind", &route.route_kind, errors);
        validate_required_text("transport_route.connect_host", &route.connect_host, errors);
        validate_route_token("transport_route.lane_id", &route.lane_id, errors);
        validate_route_token("transport_route.eye", &route.eye, errors);
        validate_route_token("transport_route.route_kind", &route.route_kind, errors);
        validate_route_token("transport_route.connect_host", &route.connect_host, errors);
        if route.connect_port == 0 {
            errors.push(ValidationError::new(format!(
                "transport route {} connect_port must be nonzero",
                route.lane_id
            )));
        }
        if !seen.insert(route.lane_id.as_str()) {
            errors.push(ValidationError::new(format!(
                "duplicate transport route for lane {}",
                route.lane_id
            )));
        }
        if !matches!(
            route.route_kind.as_str(),
            "direct_tcp_connect" | "relay_tls_client"
        ) {
            errors.push(ValidationError::new(format!(
                "transport route {} has unsupported route_kind {}",
                route.lane_id, route.route_kind
            )));
        }
        let Some(lane) = lanes_by_id.get(route.lane_id.as_str()) else {
            errors.push(ValidationError::new(format!(
                "transport route references unknown lane {}",
                route.lane_id
            )));
            continue;
        };
        if route.source_device_id != lane.source_device_id
            || route.sink_device_id != lane.sink_device_id
            || route.eye != lane.media.eye
        {
            errors.push(ValidationError::new(format!(
                "transport route {} must match lane source, sink, and eye",
                route.lane_id
            )));
        }
        match route.route_kind.as_str() {
            "direct_tcp_connect" => {
                if lane.transport.transport_kind != "lan_tcp" || lane.transport.relay_required {
                    errors.push(ValidationError::new(format!(
                        "transport route {} direct_tcp_connect requires a non-relay lan_tcp lane",
                        route.lane_id
                    )));
                }
                match runtime_endpoints.get(route.sink_device_id.as_str()) {
                    Some(endpoint) => {
                        if !endpoint.transport_receive_ports.iter().any(|binding| {
                            binding.eye == route.eye && binding.port == route.connect_port
                        }) {
                            errors.push(ValidationError::new(format!(
                                "transport route {} connect_port must match sink transport_receive_ports for {}",
                                route.lane_id, route.eye
                            )));
                        }
                    }
                    None => errors.push(ValidationError::new(format!(
                        "transport route {} sink {} has no runtime endpoint",
                        route.lane_id, route.sink_device_id
                    ))),
                }
            }
            "relay_tls_client" => {
                if lane.transport.transport_kind != "relay_tls"
                    || !lane.transport.relay_required
                    || !lane.transport.encryption_required
                {
                    errors.push(ValidationError::new(format!(
                        "transport route {} relay_tls_client requires relay_tls with encryption",
                        route.lane_id
                    )));
                }
                if route
                    .relay_channel
                    .as_deref()
                    .unwrap_or("")
                    .trim()
                    .is_empty()
                {
                    errors.push(ValidationError::new(format!(
                        "transport route {} relay_tls_client requires relay_channel",
                        route.lane_id
                    )));
                }
                if route
                    .relay_token_ref
                    .as_deref()
                    .unwrap_or("")
                    .trim()
                    .is_empty()
                {
                    errors.push(ValidationError::new(format!(
                        "transport route {} relay_tls_client requires relay_token_ref",
                        route.lane_id
                    )));
                }
            }
            _ => {}
        }
    }
    for lane in lanes {
        if !seen.contains(lane.lane_id.as_str()) {
            errors.push(ValidationError::new(format!(
                "lane {} requires a transport route",
                lane.lane_id
            )));
        }
    }
}

fn validate_route_token(label: &str, value: &str, errors: &mut Vec<ValidationError>) {
    if value.contains('|') || value.contains(';') {
        errors.push(ValidationError::new(format!(
            "{label} must not contain transport route delimiters"
        )));
    }
}

fn validate_media(
    lane_id: &str,
    media: &RemoteCameraMediaConfig,
    errors: &mut Vec<ValidationError>,
) {
    validate_required_text("track_id", &media.track_id, errors);
    validate_required_text("eye", &media.eye, errors);
    validate_required_text("track_kind", &media.track_kind, errors);
    validate_required_text("codec", &media.codec, errors);
    validate_required_text("stream_framing", &media.stream_framing, errors);
    validate_required_text("metadata_transport", &media.metadata_transport, errors);
    validate_required_text("timestamp_domain", &media.timestamp_domain, errors);
    validate_required_text(
        "high_rate_payload_plane",
        &media.high_rate_payload_plane,
        errors,
    );
    validate_route_token("media.eye", &media.eye, errors);
    validate_route_token("media.stream_framing", &media.stream_framing, errors);
    if !matches!(media.eye.as_str(), "left" | "right" | "mono") {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} has unsupported eye {}",
            media.eye
        )));
    }
    if media.track_kind != "video" {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} must use video track_kind"
        )));
    }
    if media.codec != VIDEO_CODEC_H264 {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} must use H.264 as the first Morphospace remote camera codec"
        )));
    }
    if media.high_rate_payload_plane != PAYLOAD_PLANE_BINARY_MEDIA {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} high-rate media must use binary-media payload plane"
        )));
    }
    if media.metadata_transport == "inline-json-frame-payload" {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} must not carry high-rate metadata inside JSON frame payloads"
        )));
    }
    if media.width == 0 || media.height == 0 {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} media dimensions must be nonzero"
        )));
    }
    if media.width > 1_920 || media.height > 1_920 {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} first remote-camera slice must stay at or below 1920 pixels per axis"
        )));
    }
    if media.frame_rate_hz == 0 || media.frame_rate_hz > 90 {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} frame_rate_hz must be 1..=90"
        )));
    }
    if media.bitrate_bps == 0 {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} bitrate_bps must be nonzero"
        )));
    }
}

fn validate_transport(
    lane_id: &str,
    transport: &RemoteCameraTransportConfig,
    errors: &mut Vec<ValidationError>,
) {
    validate_required_text("transport_kind", &transport.transport_kind, errors);
    if !matches!(transport.transport_kind.as_str(), "lan_tcp" | "relay_tls") {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} unsupported transport_kind {}",
            transport.transport_kind
        )));
    }
    if transport.relay_required
        && transport
            .relay_session_id
            .as_deref()
            .unwrap_or("")
            .is_empty()
    {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} relay lanes must declare relay_session_id"
        )));
    }
    if transport.transport_kind == "relay_tls" && !transport.encryption_required {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} relay_tls requires encryption_required=true"
        )));
    }
}

fn validate_queue(
    lane_id: &str,
    queue: &RemoteCameraQueuePolicy,
    errors: &mut Vec<ValidationError>,
) {
    validate_required_text("drop_policy", &queue.drop_policy, errors);
    if queue.max_buffered_packets == 0 || queue.max_buffered_packets > 256 {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} max_buffered_packets must be 1..=256"
        )));
    }
    if queue.max_buffered_bytes == 0 || queue.max_buffered_bytes > 16 * 1024 * 1024 {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} max_buffered_bytes must be 1..=16777216"
        )));
    }
    if !queue.slow_peer_close {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} must close slow peers instead of buffering forever"
        )));
    }
}

fn validate_privacy_tier(plan: &RemoteCameraSessionPlan, errors: &mut Vec<ValidationError>) {
    if !matches!(
        plan.privacy_tier.as_str(),
        PRIVACY_LOCAL_LAN_DIAGNOSTIC | PRIVACY_TRUSTED_RELAY_ENCRYPTED | PRIVACY_E2EE_CANDIDATE
    ) {
        errors.push(ValidationError::new(format!(
            "unsupported privacy_tier {}",
            plan.privacy_tier
        )));
    }
    if plan.privacy_tier != PRIVACY_LOCAL_LAN_DIAGNOSTIC
        && plan
            .lanes
            .iter()
            .any(|lane| !lane.transport.encryption_required)
    {
        errors.push(ValidationError::new(
            "non-local remote camera sessions require encrypted transport on every lane",
        ));
    }
}

fn validate_security(
    security: &RemoteCameraSecurityPolicy,
    privacy_tier: &str,
    errors: &mut Vec<ValidationError>,
) {
    if !security.visible_streaming_indicator {
        errors.push(ValidationError::new(
            "remote camera sessions require visible_streaming_indicator=true",
        ));
    }
    if !security.explicit_pairing_required {
        errors.push(ValidationError::new(
            "remote camera sessions require explicit_pairing_required=true",
        ));
    }
    validate_required_text(
        "immediate_stop_command",
        &security.immediate_stop_command,
        errors,
    );
    if privacy_tier != PRIVACY_LOCAL_LAN_DIAGNOSTIC && security.raw_media_logging {
        errors.push(ValidationError::new(
            "raw media logging is allowed only for local diagnostic sessions",
        ));
    }
}

fn validate_topology(
    plan: &RemoteCameraSessionPlan,
    devices: &BTreeMap<&str, &RemoteCameraDevice>,
    errors: &mut Vec<ValidationError>,
) {
    match plan.topology_id.as_str() {
        "quest_to_quest_two_way" => validate_q2q_topology(plan, devices, errors),
        "quest_android_phone_duplex" => validate_quest_phone_topology(plan, devices, errors),
        _ => errors.push(ValidationError::new(format!(
            "unsupported topology_id {}",
            plan.topology_id
        ))),
    }
}

fn validate_q2q_topology(
    plan: &RemoteCameraSessionPlan,
    devices: &BTreeMap<&str, &RemoteCameraDevice>,
    errors: &mut Vec<ValidationError>,
) {
    let quest_devices: Vec<&str> = devices
        .values()
        .filter(|device| device.device_kind == "quest")
        .map(|device| device.device_id.as_str())
        .collect();
    if quest_devices.len() < 2 {
        errors.push(ValidationError::new(
            "quest_to_quest_two_way requires at least two Quest devices",
        ));
        return;
    }
    let first = quest_devices[0];
    let second = quest_devices[1];
    if !has_lane_between(plan, first, second) || !has_lane_between(plan, second, first) {
        errors.push(ValidationError::new(
            "quest_to_quest_two_way requires lanes in both Quest directions",
        ));
    }
}

fn validate_quest_phone_topology(
    plan: &RemoteCameraSessionPlan,
    devices: &BTreeMap<&str, &RemoteCameraDevice>,
    errors: &mut Vec<ValidationError>,
) {
    let quest_ids: Vec<&str> = devices
        .values()
        .filter(|device| device.device_kind == "quest")
        .map(|device| device.device_id.as_str())
        .collect();
    let phone_ids: Vec<&str> = devices
        .values()
        .filter(|device| device.device_kind == "android_phone")
        .map(|device| device.device_id.as_str())
        .collect();
    if quest_ids.is_empty() || phone_ids.is_empty() {
        errors.push(ValidationError::new(
            "quest_android_phone_duplex requires a Quest and an Android phone",
        ));
        return;
    }
    let quest = quest_ids[0];
    let phone = phone_ids[0];
    if !has_lane_between(plan, quest, phone) {
        errors.push(ValidationError::new(
            "quest_android_phone_duplex requires a Quest-to-phone media lane",
        ));
    }
    if !has_lane_between(plan, phone, quest) {
        errors.push(ValidationError::new(
            "quest_android_phone_duplex requires a phone-to-Quest media lane",
        ));
    }
}

fn has_lane_between(plan: &RemoteCameraSessionPlan, source: &str, sink: &str) -> bool {
    plan.lanes.iter().any(|lane| {
        lane.source_device_id == source
            && lane.sink_device_id == sink
            && lane.media.high_rate_payload_plane == PAYLOAD_PLANE_BINARY_MEDIA
    })
}

fn validate_observability(
    observability: &RemoteCameraObservabilityPolicy,
    errors: &mut Vec<ValidationError>,
) {
    let required_counters = [
        "bytes_sent",
        "bytes_received",
        "media_packets",
        "codec_config_packets",
        "keyframes",
        "queue_drops",
        "close_reason",
    ];
    for required in required_counters {
        if !observability
            .required_counters
            .iter()
            .any(|counter| counter == required)
        {
            errors.push(ValidationError::new(format!(
                "observability must require counter {required}"
            )));
        }
    }
}

pub(crate) fn validate_required_text(label: &str, value: &str, errors: &mut Vec<ValidationError>) {
    if value.trim().is_empty() {
        errors.push(ValidationError::new(format!("{label} must not be empty")));
    }
}
