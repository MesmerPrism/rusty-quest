//! Runtime profile emission for remote camera sessions.

use std::collections::BTreeSet;

use rusty_quest_profile::{PropertyValue, RuntimeProfile};

use crate::model::*;
use crate::validation::{validate_remote_camera_session, validate_required_text};

/// Build the low-rate Quest runtime profile for one endpoint in a session plan.
///
/// The returned profile contains only launch identifiers and mode flags. It
/// intentionally omits media payloads, packet data, codec config bytes, and
/// projection buffers.
pub fn build_endpoint_runtime_profile(
    plan: &RemoteCameraSessionPlan,
    endpoint_device_id: &str,
    profile_id: impl Into<String>,
) -> Result<RuntimeProfile, Vec<ValidationError>> {
    let mut errors = validate_remote_camera_session(plan)
        .err()
        .unwrap_or_default();
    validate_required_text("endpoint_device_id", endpoint_device_id, &mut errors);

    let Some(endpoint) = plan
        .devices
        .iter()
        .find(|device| device.device_id == endpoint_device_id)
    else {
        errors.push(ValidationError::new(format!(
            "endpoint_device_id {endpoint_device_id} is not in the session plan"
        )));
        return Err(errors);
    };
    let runtime_endpoint = plan
        .runtime_endpoints
        .iter()
        .find(|candidate| candidate.device_id == endpoint_device_id);

    if !errors.is_empty() {
        return Err(errors);
    }

    let endpoint_lanes: Vec<&RemoteCameraLane> = plan
        .lanes
        .iter()
        .filter(|lane| {
            lane.source_device_id == endpoint_device_id || lane.sink_device_id == endpoint_device_id
        })
        .collect();
    let outgoing_lane_count = endpoint_lanes
        .iter()
        .filter(|lane| lane.source_device_id == endpoint_device_id)
        .count();
    let incoming_lane_count = endpoint_lanes
        .iter()
        .filter(|lane| lane.sink_device_id == endpoint_device_id)
        .count();
    let endpoint_role = match (outgoing_lane_count > 0, incoming_lane_count > 0) {
        (true, true) => "sender_receiver",
        (true, false) => "sender",
        (false, true) => "receiver",
        (false, false) => "participant",
    };
    let transport_kind = endpoint_transport_kind(&endpoint_lanes);

    let owned_android_properties = vec![
        PROP_ENABLED.to_string(),
        PROP_SESSION_ID.to_string(),
        PROP_TOPOLOGY_ID.to_string(),
        PROP_ENDPOINT_DEVICE_ID.to_string(),
        PROP_ENDPOINT_DEVICE_KIND.to_string(),
        PROP_ENDPOINT_ROLE.to_string(),
        PROP_PRIVACY_TIER.to_string(),
        PROP_LANE_COUNT.to_string(),
        PROP_INCOMING_LANE_COUNT.to_string(),
        PROP_OUTGOING_LANE_COUNT.to_string(),
        PROP_TRANSPORT_KIND.to_string(),
        PROP_ADAPTER_KIND.to_string(),
        PROP_SENDER_SOURCE_KIND.to_string(),
        PROP_SENDER_SOURCE_HOST.to_string(),
        PROP_SENDER_SOURCE_PORTS.to_string(),
        PROP_SENDER_MEDIA_PROFILES.to_string(),
        PROP_SENDER_CAMERA_ID.to_string(),
        PROP_SENDER_CAMERA_IDS.to_string(),
        PROP_SENDER_CAMERA_FACING.to_string(),
        PROP_SENDER_QUALITY_PROFILE.to_string(),
        PROP_CAMERA_PERMISSION_POLICY.to_string(),
        PROP_RECEIVER_BIND_HOST.to_string(),
        PROP_RECEIVER_PORTS.to_string(),
        PROP_TRANSPORT_BIND_HOST.to_string(),
        PROP_TRANSPORT_RECEIVE_PORTS.to_string(),
        PROP_TRANSPORT_ROUTES.to_string(),
    ];
    let set_properties = vec![
        property_value(PROP_ENABLED, "true", "quest.remote_camera.enabled"),
        property_value(
            PROP_SESSION_ID,
            &plan.session_id,
            "quest.remote_camera.session_id",
        ),
        property_value(
            PROP_TOPOLOGY_ID,
            &plan.topology_id,
            "quest.remote_camera.topology_id",
        ),
        property_value(
            PROP_ENDPOINT_DEVICE_ID,
            endpoint_device_id,
            "quest.remote_camera.endpoint_device_id",
        ),
        property_value(
            PROP_ENDPOINT_DEVICE_KIND,
            &endpoint.device_kind,
            "quest.remote_camera.endpoint_device_kind",
        ),
        property_value(
            PROP_ENDPOINT_ROLE,
            endpoint_role,
            "quest.remote_camera.endpoint_role",
        ),
        property_value(
            PROP_PRIVACY_TIER,
            &plan.privacy_tier,
            "quest.remote_camera.privacy_tier",
        ),
        property_value(
            PROP_LANE_COUNT,
            &endpoint_lanes.len().to_string(),
            "quest.remote_camera.lane_count",
        ),
        property_value(
            PROP_INCOMING_LANE_COUNT,
            &incoming_lane_count.to_string(),
            "quest.remote_camera.incoming_lane_count",
        ),
        property_value(
            PROP_OUTGOING_LANE_COUNT,
            &outgoing_lane_count.to_string(),
            "quest.remote_camera.outgoing_lane_count",
        ),
        property_value(
            PROP_TRANSPORT_KIND,
            &transport_kind,
            "quest.remote_camera.transport_kind",
        ),
        property_value(
            PROP_ADAPTER_KIND,
            runtime_endpoint
                .map(|endpoint| endpoint.adapter_kind.as_str())
                .unwrap_or("none"),
            "quest.remote_camera.adapter_kind",
        ),
        property_value(
            PROP_SENDER_SOURCE_KIND,
            runtime_endpoint
                .map(|endpoint| endpoint.sender_source_kind.as_str())
                .unwrap_or(SENDER_SOURCE_EXTERNAL_H264_SOCKET),
            "quest.remote_camera.sender_source_kind",
        ),
        property_value(
            PROP_SENDER_SOURCE_HOST,
            runtime_endpoint
                .map(|endpoint| endpoint.sender_source_host.as_str())
                .unwrap_or("none"),
            "quest.remote_camera.sender_source_host",
        ),
        property_value(
            PROP_SENDER_SOURCE_PORTS,
            &runtime_endpoint
                .map(|endpoint| format_port_bindings(&endpoint.sender_source_ports))
                .unwrap_or_else(|| "none".to_string()),
            "quest.remote_camera.sender_source_ports",
        ),
        property_value(
            PROP_SENDER_MEDIA_PROFILES,
            &format_sender_media_profiles(plan, endpoint_device_id),
            "quest.remote_camera.sender_media_profiles",
        ),
        property_value(
            PROP_SENDER_CAMERA_ID,
            runtime_endpoint
                .and_then(|endpoint| endpoint.sender_camera_id.as_deref())
                .unwrap_or("none"),
            "quest.remote_camera.sender_camera_id",
        ),
        property_value(
            PROP_SENDER_CAMERA_IDS,
            &runtime_endpoint
                .map(|endpoint| format_camera_bindings(&endpoint.sender_camera_ids))
                .unwrap_or_else(|| "none".to_string()),
            "quest.remote_camera.sender_camera_ids",
        ),
        property_value(
            PROP_SENDER_CAMERA_FACING,
            runtime_endpoint
                .and_then(|endpoint| endpoint.sender_camera_facing.as_deref())
                .unwrap_or("none"),
            "quest.remote_camera.sender_camera_facing",
        ),
        property_value(
            PROP_SENDER_QUALITY_PROFILE,
            runtime_endpoint
                .and_then(|endpoint| endpoint.sender_quality_profile.as_deref())
                .unwrap_or("none"),
            "quest.remote_camera.sender_quality_profile",
        ),
        property_value(
            PROP_CAMERA_PERMISSION_POLICY,
            runtime_endpoint
                .map(|endpoint| endpoint.camera_permission_policy.as_str())
                .unwrap_or(CAMERA_PERMISSION_NOT_REQUIRED),
            "quest.remote_camera.camera_permission_policy",
        ),
        property_value(
            PROP_RECEIVER_BIND_HOST,
            runtime_endpoint
                .map(|endpoint| endpoint.receiver_bind_host.as_str())
                .unwrap_or("none"),
            "quest.remote_camera.receiver_bind_host",
        ),
        property_value(
            PROP_RECEIVER_PORTS,
            &runtime_endpoint
                .map(|endpoint| format_port_bindings(&endpoint.receiver_ports))
                .unwrap_or_else(|| "none".to_string()),
            "quest.remote_camera.receiver_ports",
        ),
        property_value(
            PROP_TRANSPORT_BIND_HOST,
            runtime_endpoint
                .map(|endpoint| endpoint.transport_bind_host.as_str())
                .unwrap_or("none"),
            "quest.remote_camera.transport_bind_host",
        ),
        property_value(
            PROP_TRANSPORT_RECEIVE_PORTS,
            &runtime_endpoint
                .map(|endpoint| format_port_bindings(&endpoint.transport_receive_ports))
                .unwrap_or_else(|| "none".to_string()),
            "quest.remote_camera.transport_receive_ports",
        ),
        property_value(
            PROP_TRANSPORT_ROUTES,
            &format_transport_routes_for_endpoint(plan, endpoint_device_id),
            "quest.remote_camera.transport_routes",
        ),
    ];
    let profile = RuntimeProfile {
        schema: rusty_quest_profile::RUNTIME_PROFILE_SCHEMA.to_string(),
        profile_id: profile_id.into(),
        target_platform: "quest".to_string(),
        owned_android_properties,
        set_properties,
        expected_markers: vec![
            "RUSTY_QUEST_REMOTE_CAMERA_SESSION_PLAN".to_string(),
            "RUSTY_QUEST_REMOTE_CAMERA_RECEIVER_ARMED".to_string(),
            "RUSTY_QUEST_REMOTE_CAMERA_SENDER_STARTED".to_string(),
        ],
        validation_commands: Vec::new(),
    };

    rusty_quest_profile::validate_runtime_profile(&profile).map_err(|profile_errors| {
        profile_errors
            .into_iter()
            .map(|error| ValidationError::new(error.message))
            .collect::<Vec<_>>()
    })?;
    Ok(profile)
}

fn property_value(name: &str, value: &str, source_setting_id: &str) -> PropertyValue {
    PropertyValue {
        name: name.to_string(),
        value: value.to_string(),
        source_setting_id: source_setting_id.to_string(),
    }
}

fn endpoint_transport_kind(endpoint_lanes: &[&RemoteCameraLane]) -> String {
    let transport_kinds: BTreeSet<&str> = endpoint_lanes
        .iter()
        .map(|lane| lane.transport.transport_kind.as_str())
        .collect();
    if transport_kinds.len() == 1 {
        transport_kinds
            .first()
            .copied()
            .unwrap_or("none")
            .to_string()
    } else {
        "mixed".to_string()
    }
}

fn format_port_bindings(bindings: &[RemoteCameraPortBinding]) -> String {
    if bindings.is_empty() {
        return "none".to_string();
    }
    bindings
        .iter()
        .map(|binding| format!("{}:{}", binding.eye, binding.port))
        .collect::<Vec<_>>()
        .join(",")
}

fn format_camera_bindings(bindings: &[RemoteCameraCameraBinding]) -> String {
    if bindings.is_empty() {
        return "none".to_string();
    }
    bindings
        .iter()
        .map(|binding| format!("{}:{}", binding.eye, binding.camera_id))
        .collect::<Vec<_>>()
        .join(",")
}

fn format_sender_media_profiles(
    plan: &RemoteCameraSessionPlan,
    endpoint_device_id: &str,
) -> String {
    let profiles = plan
        .lanes
        .iter()
        .filter(|lane| lane.source_device_id == endpoint_device_id)
        .map(|lane| {
            format!(
                "{}:{}x{}@{}:{}",
                lane.media.eye,
                lane.media.width,
                lane.media.height,
                lane.media.frame_rate_hz,
                lane.media.bitrate_bps
            )
        })
        .collect::<Vec<_>>();
    if profiles.is_empty() {
        "none".to_string()
    } else {
        profiles.join(";")
    }
}

fn format_transport_routes_for_endpoint(
    plan: &RemoteCameraSessionPlan,
    endpoint_device_id: &str,
) -> String {
    let routes = plan
        .transport_routes
        .iter()
        .filter(|route| route.source_device_id == endpoint_device_id)
        .map(|route| {
            if route.route_kind == "direct_tcp_connect" {
                format!(
                    "{}:{}:{}",
                    route.eye, route.connect_host, route.connect_port
                )
            } else {
                format!(
                    "{}|{}|{}|{}|{}",
                    route.lane_id,
                    route.eye,
                    route.route_kind,
                    route.connect_host,
                    route.connect_port
                )
            }
        })
        .collect::<Vec<_>>();
    if routes.is_empty() {
        "none".to_string()
    } else {
        routes.join(";")
    }
}
