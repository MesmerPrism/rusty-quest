//! Validation rules for media stream session plans.

use std::collections::{BTreeMap, BTreeSet};

use crate::model::*;

/// Validate a media stream session plan.
pub fn validate_media_stream_session(
    plan: &MediaStreamSessionPlan,
) -> Result<(), Vec<ValidationError>> {
    let mut errors = Vec::new();
    validate_required_text("schema", &plan.schema, &mut errors);
    validate_required_text("session_id", &plan.session_id, &mut errors);
    validate_required_text("topology_id", &plan.topology_id, &mut errors);
    if plan.schema != MEDIA_STREAM_SESSION_SCHEMA {
        errors.push(ValidationError::new(format!(
            "unsupported media stream session schema {}",
            plan.schema
        )));
    }
    validate_privacy_tier(plan, &mut errors);
    validate_security(&plan.security, &plan.privacy_tier, &mut errors);

    let devices = validate_devices(&plan.devices, &mut errors);
    let sources = validate_sources(&plan.sources, &devices, &mut errors);
    validate_lanes(&plan.lanes, &devices, &sources, &mut errors);
    let runtime_endpoints = validate_runtime_endpoints(
        &plan.runtime_endpoints,
        &devices,
        &sources,
        &plan.lanes,
        &mut errors,
    );
    validate_runtime_endpoint_coverage(&devices, &runtime_endpoints, &mut errors);
    validate_source_binding_coverage(&sources, &runtime_endpoints, &mut errors);
    validate_transport_routes(
        &plan.transport_routes,
        &plan.lanes,
        &runtime_endpoints,
        &mut errors,
    );
    validate_topology(plan, &devices, &sources, &mut errors);
    validate_observability(&plan.observability, &mut errors);

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

fn validate_devices<'a>(
    devices: &'a [MediaStreamDevice],
    errors: &mut Vec<ValidationError>,
) -> BTreeMap<&'a str, &'a MediaStreamDevice> {
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
            "quest" | "android_phone" | "windows_pc" | "relay"
        ) {
            errors.push(ValidationError::new(format!(
                "unsupported device_kind {}",
                device.device_kind
            )));
        }
        validate_route_token("device.device_id", &device.device_id, errors);
        if seen.insert(device.device_id.as_str(), device).is_some() {
            errors.push(ValidationError::new(format!(
                "duplicate device_id {}",
                device.device_id
            )));
        }
    }
    seen
}

fn validate_sources<'a>(
    sources: &'a [MediaStreamSource],
    devices: &BTreeMap<&str, &MediaStreamDevice>,
    errors: &mut Vec<ValidationError>,
) -> BTreeMap<&'a str, &'a MediaStreamSource> {
    if sources.is_empty() {
        errors.push(ValidationError::new("sources must not be empty"));
    }
    let mut seen = BTreeMap::new();
    for source in sources {
        validate_required_text("source_id", &source.source_id, errors);
        validate_required_text("source.device_id", &source.device_id, errors);
        validate_required_text("source_family", &source.source_family, errors);
        validate_required_text("source_kind", &source.source_kind, errors);
        validate_required_text("capture_route", &source.capture_route, errors);
        validate_required_text("capture_authority", &source.capture_authority, errors);
        validate_required_text(
            "deployment_classification",
            &source.deployment_classification,
            errors,
        );
        validate_required_text("source.track_role", &source.track_role, errors);
        validate_route_token("source.source_id", &source.source_id, errors);
        validate_route_token("source.source_family", &source.source_family, errors);
        validate_route_token("source.source_kind", &source.source_kind, errors);
        validate_track_role("source.track_role", &source.track_role, errors);
        if !matches!(
            source.deployment_classification.as_str(),
            DEPLOYMENT_PRODUCTION_CANDIDATE
                | DEPLOYMENT_DIAGNOSTIC_ONLY
                | DEPLOYMENT_LAB_DEVELOPER_ONLY
        ) {
            errors.push(ValidationError::new(format!(
                "source {} has unsupported deployment_classification {}",
                source.source_id, source.deployment_classification
            )));
        }
        match devices.get(source.device_id.as_str()) {
            Some(device) if device.device_kind == "relay" => {
                errors.push(ValidationError::new(format!(
                    "source {} must target a media endpoint, not a relay",
                    source.source_id
                )));
            }
            Some(_) => {}
            None => errors.push(ValidationError::new(format!(
                "source {} references unknown device_id {}",
                source.source_id, source.device_id
            ))),
        }
        validate_source_policy(source, errors);
        if seen.insert(source.source_id.as_str(), source).is_some() {
            errors.push(ValidationError::new(format!(
                "duplicate source_id {}",
                source.source_id
            )));
        }
    }
    seen
}

fn validate_source_policy(source: &MediaStreamSource, errors: &mut Vec<ValidationError>) {
    match source.source_kind.as_str() {
        SOURCE_KIND_EXTERNAL_H264_SOCKET => {
            require_capture_authority(source, CAPTURE_AUTHORITY_EXTERNAL_H264_ADAPTER, errors);
        }
        SOURCE_KIND_CAMERA2_MEDIACODEC_SURFACE => validate_camera_source(source, errors),
        SOURCE_KIND_DISPLAY_COMPOSITE_MEDIAPROJECTION_SURFACE => {
            validate_display_composite_source(source, errors);
        }
        SOURCE_KIND_SHELL_DISPLAY_MIRROR_SURFACE => validate_shell_display_source(source, errors),
        SOURCE_KIND_DIAGNOSTIC_SYNTHETIC_SURFACE => {
            require_capture_authority(source, CAPTURE_AUTHORITY_DIAGNOSTIC_SYNTHETIC, errors);
        }
        _ => errors.push(ValidationError::new(format!(
            "source {} has unsupported source_kind {}",
            source.source_id, source.source_kind
        ))),
    }
}

fn validate_camera_source(source: &MediaStreamSource, errors: &mut Vec<ValidationError>) {
    require_capture_authority(source, CAPTURE_AUTHORITY_ANDROID_CAMERA_PERMISSION, errors);
    if source.developer_shell_required {
        errors.push(ValidationError::new(format!(
            "camera2 source {} must not require developer shell",
            source.source_id
        )));
    }
    let Some(camera) = source.camera.as_ref() else {
        errors.push(ValidationError::new(format!(
            "camera2_mediacodec_surface source {} requires camera capture metadata",
            source.source_id
        )));
        return;
    };
    validate_required_text("camera.camera_id", &camera.camera_id, errors);
    validate_required_text("camera.camera_facing", &camera.camera_facing, errors);
    validate_required_text(
        "camera.permission_policy",
        &camera.permission_policy,
        errors,
    );
    if camera.permission_policy != CAMERA_PERMISSION_REQUIRED {
        errors.push(ValidationError::new(format!(
            "camera2 source {} requires camera_permission_required",
            source.source_id
        )));
    }
}

fn validate_display_composite_source(
    source: &MediaStreamSource,
    errors: &mut Vec<ValidationError>,
) {
    require_capture_authority(source, CAPTURE_AUTHORITY_ANDROID_MEDIAPROJECTION, errors);
    if source.developer_shell_required {
        errors.push(ValidationError::new(format!(
            "display-composite source {} must not require developer shell",
            source.source_id
        )));
    }
    if !source.consent_required {
        errors.push(ValidationError::new(format!(
            "display-composite source {} requires consent_required=true",
            source.source_id
        )));
    }
    let Some(display) = source.display.as_ref() else {
        errors.push(ValidationError::new(format!(
            "display_composite_mediaprojection_mediacodec_surface source {} requires display capture metadata",
            source.source_id
        )));
        return;
    };
    validate_display_descriptor(&source.source_id, display, errors);
    if display.consent_state != "android-mediaprojection-consent-required" {
        errors.push(ValidationError::new(format!(
            "display-composite source {} requires android-mediaprojection-consent-required consent_state",
            source.source_id
        )));
    }
}

fn validate_shell_display_source(source: &MediaStreamSource, errors: &mut Vec<ValidationError>) {
    require_capture_authority(source, CAPTURE_AUTHORITY_ADB_SHELL_HIDDEN_API, errors);
    if source.deployment_classification != DEPLOYMENT_LAB_DEVELOPER_ONLY {
        errors.push(ValidationError::new(format!(
            "shell display mirror sources must be lab_developer_only: {}",
            source.source_id
        )));
    }
    if !source.developer_shell_required {
        errors.push(ValidationError::new(format!(
            "shell display mirror source {} requires developer_shell_required=true",
            source.source_id
        )));
    }
    if source.consent_required {
        errors.push(ValidationError::new(format!(
            "shell display mirror source {} must not claim MediaProjection consent",
            source.source_id
        )));
    }
    let Some(display) = source.display.as_ref() else {
        errors.push(ValidationError::new(format!(
            "shell_display_mirror_mediacodec_surface source {} requires display capture metadata",
            source.source_id
        )));
        return;
    };
    validate_display_descriptor(&source.source_id, display, errors);
    if display.consent_state != "developer-shell-only-no-mediaprojection-consent" {
        errors.push(ValidationError::new(format!(
            "shell display mirror source {} requires developer-shell-only consent_state",
            source.source_id
        )));
    }
}

fn require_capture_authority(
    source: &MediaStreamSource,
    expected: &str,
    errors: &mut Vec<ValidationError>,
) {
    if source.capture_authority != expected {
        errors.push(ValidationError::new(format!(
            "source {} with kind {} requires capture_authority {}",
            source.source_id, source.source_kind, expected
        )));
    }
}

fn validate_display_descriptor(
    source_id: &str,
    display: &DisplayCaptureDescriptor,
    errors: &mut Vec<ValidationError>,
) {
    validate_required_text("display.display_id", &display.display_id, errors);
    validate_required_text("display.rotation", &display.rotation, errors);
    validate_required_text(
        "display.protected_content_policy",
        &display.protected_content_policy,
        errors,
    );
    validate_required_text("display.consent_state", &display.consent_state, errors);
    validate_required_text(
        "display.privacy_indicator",
        &display.privacy_indicator,
        errors,
    );
    validate_required_text(
        "display.foreground_package_reporting",
        &display.foreground_package_reporting,
        errors,
    );
    if display.density_dpi == 0 {
        errors.push(ValidationError::new(format!(
            "display source {source_id} density_dpi must be nonzero"
        )));
    }
    if display.content_crop.width == 0 || display.content_crop.height == 0 {
        errors.push(ValidationError::new(format!(
            "display source {source_id} content crop dimensions must be nonzero"
        )));
    }
    if !matches!(
        display.protected_content_policy.as_str(),
        "omit-protected-content" | "black-frame-protected-content" | "unknown-lab-observe-only"
    ) {
        errors.push(ValidationError::new(format!(
            "display source {source_id} has unsupported protected_content_policy {}",
            display.protected_content_policy
        )));
    }
    if !matches!(
        display.privacy_indicator.as_str(),
        "system-capture-indicator-required" | "developer-lab-indicator-required"
    ) {
        errors.push(ValidationError::new(format!(
            "display source {source_id} has unsupported privacy_indicator {}",
            display.privacy_indicator
        )));
    }
    if !matches!(
        display.foreground_package_reporting.as_str(),
        "not-collected" | "low-rate-debug-only"
    ) {
        errors.push(ValidationError::new(format!(
            "display source {source_id} has unsupported foreground_package_reporting {}",
            display.foreground_package_reporting
        )));
    }
}

fn validate_lanes(
    lanes: &[MediaStreamLane],
    devices: &BTreeMap<&str, &MediaStreamDevice>,
    sources: &BTreeMap<&str, &MediaStreamSource>,
    errors: &mut Vec<ValidationError>,
) {
    if lanes.is_empty() {
        errors.push(ValidationError::new("lanes must not be empty"));
    }
    let mut lane_ids = BTreeSet::new();
    for lane in lanes {
        validate_required_text("lane_id", &lane.lane_id, errors);
        validate_required_text("direction", &lane.direction, errors);
        validate_required_text("source_id", &lane.source_id, errors);
        validate_required_text("source_device_id", &lane.source_device_id, errors);
        validate_required_text("sink_device_id", &lane.sink_device_id, errors);
        validate_route_token("lane.lane_id", &lane.lane_id, errors);
        validate_route_token("lane.source_id", &lane.source_id, errors);
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
        let source = match sources.get(lane.source_id.as_str()) {
            Some(source) => Some(*source),
            None => {
                errors.push(ValidationError::new(format!(
                    "lane {} references unknown source_id {}",
                    lane.lane_id, lane.source_id
                )));
                None
            }
        };
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
        if let Some(source) = source {
            if source.device_id != lane.source_device_id {
                errors.push(ValidationError::new(format!(
                    "lane {} source_device_id must match source {} device_id",
                    lane.lane_id, lane.source_id
                )));
            }
            if source.track_role != lane.media.track_role {
                errors.push(ValidationError::new(format!(
                    "lane {} media track_role must match source {} track_role",
                    lane.lane_id, lane.source_id
                )));
            }
        }
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
    runtime_endpoints: &'a [MediaStreamRuntimeEndpoint],
    devices: &BTreeMap<&str, &MediaStreamDevice>,
    sources: &BTreeMap<&str, &MediaStreamSource>,
    lanes: &[MediaStreamLane],
    errors: &mut Vec<ValidationError>,
) -> BTreeMap<&'a str, &'a MediaStreamRuntimeEndpoint> {
    let mut seen = BTreeMap::new();
    for endpoint in runtime_endpoints {
        validate_required_text("runtime_endpoint.device_id", &endpoint.device_id, errors);
        validate_required_text(
            "runtime_endpoint.adapter_kind",
            &endpoint.adapter_kind,
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
            "quest_manifold_broker_android"
                | "android_companion"
                | "windows_hostess"
                | "termux_sidecar"
                | "pc_viewer"
        ) {
            errors.push(ValidationError::new(format!(
                "runtime endpoint {} has unsupported adapter_kind {}",
                endpoint.device_id, endpoint.adapter_kind
            )));
        }
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
        validate_source_bindings(endpoint, sources, errors);
        let sources_required = lanes
            .iter()
            .any(|lane| lane.source_device_id == endpoint.device_id);
        let sinks_required = lanes
            .iter()
            .any(|lane| lane.sink_device_id == endpoint.device_id);
        if sources_required && endpoint.source_bindings.is_empty() {
            errors.push(ValidationError::new(format!(
                "runtime endpoint {} source_bindings must not be empty",
                endpoint.device_id
            )));
        }
        validate_port_bindings(
            &endpoint.device_id,
            "receiver_ports",
            &endpoint.receiver_ports,
            sinks_required,
            errors,
        );
        validate_port_bindings(
            &endpoint.device_id,
            "transport_receive_ports",
            &endpoint.transport_receive_ports,
            sinks_required,
            errors,
        );
    }
    seen
}

fn validate_source_bindings(
    endpoint: &MediaStreamRuntimeEndpoint,
    sources: &BTreeMap<&str, &MediaStreamSource>,
    errors: &mut Vec<ValidationError>,
) {
    let mut seen = BTreeSet::new();
    for binding in &endpoint.source_bindings {
        validate_required_text("source_binding.source_id", &binding.source_id, errors);
        validate_required_text("source_binding.track_role", &binding.track_role, errors);
        validate_required_text("source_binding.source_host", &binding.source_host, errors);
        validate_track_role("source_binding.track_role", &binding.track_role, errors);
        validate_route_token("source_binding.source_id", &binding.source_id, errors);
        if binding.source_port == 0 {
            errors.push(ValidationError::new(format!(
                "runtime endpoint {} source_binding port for {} must be nonzero",
                endpoint.device_id, binding.track_role
            )));
        }
        match sources.get(binding.source_id.as_str()) {
            Some(source) => {
                if source.device_id != endpoint.device_id {
                    errors.push(ValidationError::new(format!(
                        "runtime endpoint {} source_binding {} must belong to same device",
                        endpoint.device_id, binding.source_id
                    )));
                }
                if source.track_role != binding.track_role {
                    errors.push(ValidationError::new(format!(
                        "runtime endpoint {} source_binding {} track_role must match source",
                        endpoint.device_id, binding.source_id
                    )));
                }
            }
            None => errors.push(ValidationError::new(format!(
                "runtime endpoint {} source_binding references unknown source_id {}",
                endpoint.device_id, binding.source_id
            ))),
        }
        if !seen.insert(binding.source_id.as_str()) {
            errors.push(ValidationError::new(format!(
                "runtime endpoint {} repeats source_binding {}",
                endpoint.device_id, binding.source_id
            )));
        }
    }
}

fn validate_runtime_endpoint_coverage(
    devices: &BTreeMap<&str, &MediaStreamDevice>,
    runtime_endpoints: &BTreeMap<&str, &MediaStreamRuntimeEndpoint>,
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

fn validate_source_binding_coverage(
    sources: &BTreeMap<&str, &MediaStreamSource>,
    runtime_endpoints: &BTreeMap<&str, &MediaStreamRuntimeEndpoint>,
    errors: &mut Vec<ValidationError>,
) {
    for source in sources.values() {
        let Some(endpoint) = runtime_endpoints.get(source.device_id.as_str()) else {
            continue;
        };
        if !endpoint
            .source_bindings
            .iter()
            .any(|binding| binding.source_id == source.source_id)
        {
            errors.push(ValidationError::new(format!(
                "source {} requires a source_binding on endpoint {}",
                source.source_id, source.device_id
            )));
        }
    }
}

fn validate_port_bindings(
    device_id: &str,
    field: &str,
    bindings: &[MediaStreamPortBinding],
    required: bool,
    errors: &mut Vec<ValidationError>,
) {
    if required && bindings.is_empty() {
        errors.push(ValidationError::new(format!(
            "runtime endpoint {device_id} {field} must not be empty"
        )));
    }
    let mut seen_roles = BTreeSet::new();
    let mut seen_ports = BTreeSet::new();
    for binding in bindings {
        validate_required_text(
            "runtime_endpoint.port.track_role",
            &binding.track_role,
            errors,
        );
        validate_track_role(
            "runtime_endpoint.port.track_role",
            &binding.track_role,
            errors,
        );
        if binding.port == 0 {
            errors.push(ValidationError::new(format!(
                "runtime endpoint {device_id} {field} port for {} must be nonzero",
                binding.track_role
            )));
        }
        if !seen_roles.insert(binding.track_role.as_str()) {
            errors.push(ValidationError::new(format!(
                "runtime endpoint {device_id} {field} repeats track_role {}",
                binding.track_role
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

fn validate_transport_routes(
    routes: &[MediaStreamTransportRoute],
    lanes: &[MediaStreamLane],
    runtime_endpoints: &BTreeMap<&str, &MediaStreamRuntimeEndpoint>,
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
        validate_required_text("transport_route.track_role", &route.track_role, errors);
        validate_required_text("transport_route.route_kind", &route.route_kind, errors);
        validate_required_text("transport_route.connect_host", &route.connect_host, errors);
        validate_route_token("transport_route.lane_id", &route.lane_id, errors);
        validate_track_role("transport_route.track_role", &route.track_role, errors);
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
            || route.track_role != lane.media.track_role
        {
            errors.push(ValidationError::new(format!(
                "transport route {} must match lane source, sink, and track_role",
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
                            binding.track_role == route.track_role
                                && binding.port == route.connect_port
                        }) {
                            errors.push(ValidationError::new(format!(
                                "transport route {} connect_port must match sink transport_receive_ports for {}",
                                route.lane_id, route.track_role
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

fn validate_media(
    lane_id: &str,
    media: &MediaStreamMediaConfig,
    errors: &mut Vec<ValidationError>,
) {
    validate_required_text("track_id", &media.track_id, errors);
    validate_required_text("track_role", &media.track_role, errors);
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
    validate_track_role("media.track_role", &media.track_role, errors);
    validate_route_token("media.track_role", &media.track_role, errors);
    validate_route_token("media.stream_framing", &media.stream_framing, errors);
    if media.track_kind != "video" {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} must use video track_kind"
        )));
    }
    if media.codec != VIDEO_CODEC_H264 {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} must use H.264 as the first reusable media stream codec"
        )));
    }
    if media.stream_framing != STREAM_FRAMING_DIAGNOSTIC_H264 {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} must use diagnostic-h264-packet-stream framing for the first reusable slice"
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
    if media.width > 4_096 || media.height > 4_096 {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} first media-stream slice must stay at or below 4096 pixels per axis"
        )));
    }
    if media.frame_rate_hz == 0 || media.frame_rate_hz > 120 {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} frame_rate_hz must be 1..=120"
        )));
    }
    if media.bitrate_bps == 0 {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} bitrate_bps must be nonzero"
        )));
    }
    if !(64 * 1024..=8 * 1024 * 1024).contains(&media.max_packet_bytes) {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} max_packet_bytes must be 65536..=8388608"
        )));
    }
}

fn validate_transport(
    lane_id: &str,
    transport: &MediaStreamTransportConfig,
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
    queue: &MediaStreamQueuePolicy,
    errors: &mut Vec<ValidationError>,
) {
    validate_required_text("drop_policy", &queue.drop_policy, errors);
    if queue.max_buffered_packets == 0 || queue.max_buffered_packets > 256 {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} max_buffered_packets must be 1..=256"
        )));
    }
    if queue.max_buffered_bytes == 0 || queue.max_buffered_bytes > 32 * 1024 * 1024 {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} max_buffered_bytes must be 1..=33554432"
        )));
    }
    if !queue.slow_peer_close {
        errors.push(ValidationError::new(format!(
            "lane {lane_id} must close slow peers instead of buffering forever"
        )));
    }
}

fn validate_privacy_tier(plan: &MediaStreamSessionPlan, errors: &mut Vec<ValidationError>) {
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
            "non-local media stream sessions require encrypted transport on every lane",
        ));
    }
}

fn validate_security(
    security: &MediaStreamSecurityPolicy,
    privacy_tier: &str,
    errors: &mut Vec<ValidationError>,
) {
    if !security.visible_streaming_indicator {
        errors.push(ValidationError::new(
            "media stream sessions require visible_streaming_indicator=true",
        ));
    }
    if !security.explicit_pairing_required {
        errors.push(ValidationError::new(
            "media stream sessions require explicit_pairing_required=true",
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
    plan: &MediaStreamSessionPlan,
    devices: &BTreeMap<&str, &MediaStreamDevice>,
    sources: &BTreeMap<&str, &MediaStreamSource>,
    errors: &mut Vec<ValidationError>,
) {
    match plan.topology_id.as_str() {
        "quest_display_to_pc" => validate_quest_display_to_pc(plan, devices, sources, errors),
        "quest_to_quest_two_way" => validate_quest_to_quest_two_way(plan, devices, errors),
        "quest_android_phone_duplex" => validate_quest_android_phone_duplex(plan, devices, errors),
        "diagnostic_loopback" => {}
        _ => errors.push(ValidationError::new(format!(
            "unsupported topology_id {}",
            plan.topology_id
        ))),
    }
}

fn validate_quest_to_quest_two_way(
    plan: &MediaStreamSessionPlan,
    devices: &BTreeMap<&str, &MediaStreamDevice>,
    errors: &mut Vec<ValidationError>,
) {
    let quest_devices = devices
        .values()
        .filter(|device| device.device_kind == "quest")
        .map(|device| device.device_id.as_str())
        .collect::<Vec<_>>();
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

fn validate_quest_android_phone_duplex(
    plan: &MediaStreamSessionPlan,
    devices: &BTreeMap<&str, &MediaStreamDevice>,
    errors: &mut Vec<ValidationError>,
) {
    let quest_ids = devices
        .values()
        .filter(|device| device.device_kind == "quest")
        .map(|device| device.device_id.as_str())
        .collect::<Vec<_>>();
    let phone_ids = devices
        .values()
        .filter(|device| device.device_kind == "android_phone")
        .map(|device| device.device_id.as_str())
        .collect::<Vec<_>>();
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

fn has_lane_between(plan: &MediaStreamSessionPlan, source: &str, sink: &str) -> bool {
    plan.lanes.iter().any(|lane| {
        lane.source_device_id == source
            && lane.sink_device_id == sink
            && lane.media.high_rate_payload_plane == PAYLOAD_PLANE_BINARY_MEDIA
    })
}

fn validate_quest_display_to_pc(
    plan: &MediaStreamSessionPlan,
    devices: &BTreeMap<&str, &MediaStreamDevice>,
    sources: &BTreeMap<&str, &MediaStreamSource>,
    errors: &mut Vec<ValidationError>,
) {
    let has_display_lane = plan.lanes.iter().any(|lane| {
        let source = sources.get(lane.source_id.as_str());
        let source_device = devices.get(lane.source_device_id.as_str());
        let sink_device = devices.get(lane.sink_device_id.as_str());
        matches!(source_device, Some(device) if device.device_kind == "quest")
            && matches!(sink_device, Some(device) if device.device_kind == "windows_pc")
            && matches!(
                source.map(|source| source.source_kind.as_str()),
                Some(SOURCE_KIND_DISPLAY_COMPOSITE_MEDIAPROJECTION_SURFACE)
                    | Some(SOURCE_KIND_SHELL_DISPLAY_MIRROR_SURFACE)
            )
    });
    if !has_display_lane {
        errors.push(ValidationError::new(
            "quest_display_to_pc requires a Quest display source lane to a windows_pc sink",
        ));
    }
}

fn validate_observability(
    observability: &MediaStreamObservabilityPolicy,
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
        "capture_to_encode_ms",
        "encode_to_receive_ms",
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

fn validate_track_role(label: &str, value: &str, errors: &mut Vec<ValidationError>) {
    if !matches!(value, "left" | "right" | "mono" | "display") {
        errors.push(ValidationError::new(format!(
            "{label} has unsupported track role {value}"
        )));
    }
}

fn validate_route_token(label: &str, value: &str, errors: &mut Vec<ValidationError>) {
    if value.contains('|') || value.contains(';') {
        errors.push(ValidationError::new(format!(
            "{label} must not contain transport route delimiters"
        )));
    }
}

fn validate_required_text(label: &str, value: &str, errors: &mut Vec<ValidationError>) {
    if value.trim().is_empty() {
        errors.push(ValidationError::new(format!("{label} must not be empty")));
    }
}
