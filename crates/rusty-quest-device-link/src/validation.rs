//! Validation rules for device-link reports.

use std::collections::BTreeSet;

use crate::model::*;

/// Validate a Quest device-link report.
pub fn validate_device_link_report(report: &DeviceLinkReport) -> Result<(), Vec<ValidationError>> {
    let mut errors = Vec::new();

    validate_required_text("schema", &report.schema, &mut errors);
    validate_required_text("link_id", &report.link_id, &mut errors);
    validate_status("status", &report.status, &mut errors);
    if report.schema != DEVICE_LINK_SCHEMA {
        errors.push(ValidationError::new(format!(
            "unsupported device-link schema {}",
            report.schema
        )));
    }
    if report.observed_at_ms == 0 {
        errors.push(ValidationError::new("observed_at_ms must be nonzero"));
    }

    validate_device_identity(&report.device_identity, &mut errors);
    validate_host_tools(&report.host_tools, &mut errors);
    let tunnel_ids = validate_tunnels(&report.tunnels, &mut errors);
    validate_broker_endpoints(&report.broker_endpoints, &tunnel_ids, &mut errors);
    validate_runtime_subscribers(&report.runtime_subscribers, &mut errors);
    validate_command_results(&report.command_results, &mut errors);
    validate_stream_capabilities(&report.stream_capabilities, &mut errors);
    validate_issues(&report.issues, &mut errors);
    validate_report_status_coherence(report, &mut errors);

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

fn validate_device_identity(identity: &QuestDeviceIdentity, errors: &mut Vec<ValidationError>) {
    validate_required_text("device_identity.serial", &identity.serial, errors);
    validate_required_text(
        "device_identity.transport_kind",
        &identity.transport_kind,
        errors,
    );
    validate_required_text("device_identity.adb_state", &identity.adb_state, errors);
    validate_required_text("device_identity.model", &identity.model, errors);
    if !matches!(
        identity.transport_kind.as_str(),
        TRANSPORT_ADB_USB | TRANSPORT_ADB_WIFI
    ) {
        errors.push(ValidationError::new(format!(
            "unsupported device_identity transport_kind {}",
            identity.transport_kind
        )));
    }
}

fn validate_host_tools(tools: &[HostToolState], errors: &mut Vec<ValidationError>) {
    let mut seen = BTreeSet::new();
    for tool in tools {
        validate_required_text("host_tool.tool_id", &tool.tool_id, errors);
        validate_required_text("host_tool.kind", &tool.kind, errors);
        validate_status("host_tool.status", &tool.status, errors);
        if !seen.insert(tool.tool_id.as_str()) {
            errors.push(ValidationError::new(format!(
                "duplicate host tool {}",
                tool.tool_id
            )));
        }
        if tool.required && tool.status == STATUS_PASS {
            if tool.path.as_deref().unwrap_or("").trim().is_empty()
                && tool.version.as_deref().unwrap_or("").trim().is_empty()
            {
                errors.push(ValidationError::new(format!(
                    "required host tool {} must include path or version evidence",
                    tool.tool_id
                )));
            }
        }
    }
}

fn validate_tunnels(
    tunnels: &[TunnelState],
    errors: &mut Vec<ValidationError>,
) -> BTreeSet<String> {
    let mut ids = BTreeSet::new();
    let mut local_bindings = BTreeSet::new();
    for tunnel in tunnels {
        validate_required_text("tunnel.tunnel_id", &tunnel.tunnel_id, errors);
        validate_required_text("tunnel.transport_kind", &tunnel.transport_kind, errors);
        validate_status("tunnel.status", &tunnel.status, errors);
        validate_required_text("tunnel.host", &tunnel.host, errors);
        if !ids.insert(tunnel.tunnel_id.clone()) {
            errors.push(ValidationError::new(format!(
                "duplicate tunnel_id {}",
                tunnel.tunnel_id
            )));
        }
        if tunnel.transport_kind != TRANSPORT_ADB_FORWARD {
            errors.push(ValidationError::new(format!(
                "unsupported tunnel transport_kind {}",
                tunnel.transport_kind
            )));
        }
        if tunnel.transport_kind == TRANSPORT_ADB_FORWARD {
            match (tunnel.local_port, tunnel.device_port) {
                (Some(local), Some(device)) if local > 0 && device > 0 => {
                    if !local_bindings.insert((tunnel.host.as_str(), local)) {
                        errors.push(ValidationError::new(format!(
                            "duplicate local tunnel binding {}:{}",
                            tunnel.host, local
                        )));
                    }
                }
                _ => errors.push(ValidationError::new(format!(
                    "ADB forward tunnel {} requires nonzero local_port and device_port",
                    tunnel.tunnel_id
                ))),
            }
        }
    }
    ids
}

fn validate_broker_endpoints(
    endpoints: &[BrokerEndpointState],
    tunnel_ids: &BTreeSet<String>,
    errors: &mut Vec<ValidationError>,
) {
    let mut seen = BTreeSet::new();
    for endpoint in endpoints {
        validate_required_text("broker_endpoint.endpoint_id", &endpoint.endpoint_id, errors);
        validate_status("broker_endpoint.status", &endpoint.status, errors);
        validate_required_text("broker_endpoint.protocol", &endpoint.protocol, errors);
        validate_required_text("broker_endpoint.authority", &endpoint.authority, errors);
        validate_required_text("broker_endpoint.host", &endpoint.host, errors);
        validate_required_text("broker_endpoint.path", &endpoint.path, errors);
        validate_required_text(
            "broker_endpoint.command_envelope_schema",
            &endpoint.command_envelope_schema,
            errors,
        );
        if !seen.insert(endpoint.endpoint_id.as_str()) {
            errors.push(ValidationError::new(format!(
                "duplicate broker endpoint {}",
                endpoint.endpoint_id
            )));
        }
        if endpoint.protocol != PROTOCOL_WEBSOCKET {
            errors.push(ValidationError::new(format!(
                "broker endpoint {} must use websocket protocol",
                endpoint.endpoint_id
            )));
        }
        if endpoint.path != MANIFOLD_EVENTS_PATH {
            errors.push(ValidationError::new(format!(
                "broker endpoint {} must use {}",
                endpoint.endpoint_id, MANIFOLD_EVENTS_PATH
            )));
        }
        if endpoint.port == 0 {
            errors.push(ValidationError::new(format!(
                "broker endpoint {} port must be nonzero",
                endpoint.endpoint_id
            )));
        }
        if let Some(tunnel_id) = endpoint.routed_through_tunnel_id.as_deref() {
            if !tunnel_ids.contains(tunnel_id) {
                errors.push(ValidationError::new(format!(
                    "broker endpoint {} references unknown tunnel {}",
                    endpoint.endpoint_id, tunnel_id
                )));
            }
        }
        if endpoint.high_rate_payload_allowed {
            errors.push(ValidationError::new(format!(
                "broker endpoint {} must not allow high-rate payloads",
                endpoint.endpoint_id
            )));
        }
    }
}

fn validate_runtime_subscribers(
    subscribers: &[RuntimeSubscriberState],
    errors: &mut Vec<ValidationError>,
) {
    let mut seen = BTreeSet::new();
    for subscriber in subscribers {
        validate_required_text(
            "runtime_subscriber.subscriber_id",
            &subscriber.subscriber_id,
            errors,
        );
        validate_required_text(
            "runtime_subscriber.runtime_app_id",
            &subscriber.runtime_app_id,
            errors,
        );
        validate_required_text(
            "runtime_subscriber.request_stream_id",
            &subscriber.request_stream_id,
            errors,
        );
        validate_required_text(
            "runtime_subscriber.receipt_stream_id",
            &subscriber.receipt_stream_id,
            errors,
        );
        if !matches!(
            subscriber.status.as_str(),
            "connected" | "missing" | "unknown" | STATUS_SKIPPED
        ) {
            errors.push(ValidationError::new(format!(
                "runtime subscriber {} has unsupported status {}",
                subscriber.subscriber_id, subscriber.status
            )));
        }
        if !seen.insert(subscriber.subscriber_id.as_str()) {
            errors.push(ValidationError::new(format!(
                "duplicate runtime subscriber {}",
                subscriber.subscriber_id
            )));
        }
        if subscriber.receipt_required
            && subscriber.status == "connected"
            && subscriber.last_dispatch_delivered_count.unwrap_or(0) == 0
        {
            errors.push(ValidationError::new(format!(
                "runtime subscriber {} is connected but has no delivered dispatch evidence",
                subscriber.subscriber_id
            )));
        }
    }
}

fn validate_command_results(results: &[CommandResultReport], errors: &mut Vec<ValidationError>) {
    let mut seen = BTreeSet::new();
    for result in results {
        validate_required_text("command_result.result_id", &result.result_id, errors);
        validate_required_text("command_result.route_id", &result.route_id, errors);
        validate_required_text("command_result.request_id", &result.request_id, errors);
        validate_required_text("command_result.command", &result.command, errors);
        validate_required_text(
            "command_result.transport_kind",
            &result.transport_kind,
            errors,
        );
        validate_status("command_result.status", &result.status, errors);
        if !seen.insert(result.result_id.as_str()) {
            errors.push(ValidationError::new(format!(
                "duplicate command result {}",
                result.result_id
            )));
        }
        if result.required_stages.is_empty() {
            errors.push(ValidationError::new(format!(
                "command result {} required_stages must not be empty",
                result.result_id
            )));
        }
        if result.transport_kind == TRANSPORT_APP_PRIVATE_JSON
            && result
                .required_stages
                .iter()
                .any(|stage| stage == "authority_accepted")
        {
            errors.push(ValidationError::new(format!(
                "command result {} cannot claim Manifold authority over app-private JSON",
                result.result_id
            )));
        }
        let passed_stages = result
            .observed_stages
            .iter()
            .filter(|stage| stage.status == STATUS_PASS)
            .map(|stage| stage.stage.as_str())
            .collect::<BTreeSet<_>>();
        if result.status == STATUS_PASS || result.applied {
            for required in &result.required_stages {
                if !passed_stages.contains(required.as_str()) {
                    errors.push(ValidationError::new(format!(
                        "command result {} missing passing required stage {}",
                        result.result_id, required
                    )));
                }
            }
        }
        if result.applied && !passed_stages.contains("runtime_accepted") {
            errors.push(ValidationError::new(format!(
                "command result {} cannot be applied without runtime_accepted",
                result.result_id
            )));
        }
        if result.applied && !passed_stages.contains("applied") {
            errors.push(ValidationError::new(format!(
                "command result {} cannot be applied without applied stage evidence",
                result.result_id
            )));
        }
        if result.applied && result.runtime_dispatch_delivered_count.unwrap_or(0) == 0 {
            errors.push(ValidationError::new(format!(
                "command result {} requires nonzero runtime_dispatch_delivered_count",
                result.result_id
            )));
        }
        if result.transport_kind == TRANSPORT_MANIFOLD_WEBSOCKET
            && result
                .required_stages
                .iter()
                .any(|stage| stage == "runtime_accepted")
            && result
                .runtime_receipt_stream
                .as_deref()
                .unwrap_or("")
                .trim()
                .is_empty()
        {
            errors.push(ValidationError::new(format!(
                "command result {} requires runtime_receipt_stream",
                result.result_id
            )));
        }
    }
}

fn validate_stream_capabilities(
    capabilities: &[StreamCapabilityDescriptor],
    errors: &mut Vec<ValidationError>,
) {
    let mut seen = BTreeSet::new();
    for capability in capabilities {
        validate_required_text(
            "stream_capability.capability_id",
            &capability.capability_id,
            errors,
        );
        validate_required_text("stream_capability.stream_id", &capability.stream_id, errors);
        validate_required_text(
            "stream_capability.semantic_family",
            &capability.semantic_family,
            errors,
        );
        validate_required_text(
            "stream_capability.transport_kind",
            &capability.transport_kind,
            errors,
        );
        validate_required_text(
            "stream_capability.payload_plane",
            &capability.payload_plane,
            errors,
        );
        validate_required_text(
            "stream_capability.rate_class",
            &capability.rate_class,
            errors,
        );
        validate_required_text(
            "stream_capability.reliability",
            &capability.reliability,
            errors,
        );
        validate_required_text("stream_capability.direction", &capability.direction, errors);
        validate_required_text(
            "stream_capability.clock_policy",
            &capability.clock_policy,
            errors,
        );
        validate_required_text(
            "stream_capability.queue_policy",
            &capability.queue_policy,
            errors,
        );
        if !seen.insert(capability.capability_id.as_str()) {
            errors.push(ValidationError::new(format!(
                "duplicate stream capability {}",
                capability.capability_id
            )));
        }
        if !matches!(
            capability.rate_class.as_str(),
            RATE_CLASS_CONTROL
                | RATE_CLASS_LOW_RATE
                | RATE_CLASS_SAMPLE_CLOCKED
                | RATE_CLASS_HIGH_RATE
        ) {
            errors.push(ValidationError::new(format!(
                "stream capability {} has unsupported rate_class {}",
                capability.capability_id, capability.rate_class
            )));
        }
        if matches!(
            capability.rate_class.as_str(),
            RATE_CLASS_SAMPLE_CLOCKED | RATE_CLASS_HIGH_RATE
        ) && capability.high_rate_json_payload
        {
            errors.push(ValidationError::new(format!(
                "stream capability {} must not carry high-rate payloads as JSON",
                capability.capability_id
            )));
        }
        if capability.rate_class == RATE_CLASS_HIGH_RATE
            && capability.payload_plane == PAYLOAD_PLANE_JSON_EVENT
        {
            errors.push(ValidationError::new(format!(
                "stream capability {} high-rate streams require a non-JSON payload plane",
                capability.capability_id
            )));
        }
        if capability.transport_kind == TRANSPORT_MANIFOLD_WEBSOCKET
            && capability.rate_class != RATE_CLASS_CONTROL
            && capability.payload_plane == PAYLOAD_PLANE_JSON_EVENT
        {
            errors.push(ValidationError::new(format!(
                "stream capability {} uses Manifold WebSocket JSON outside control-rate traffic",
                capability.capability_id
            )));
        }
    }
}

fn validate_issues(issues: &[DeviceLinkIssue], errors: &mut Vec<ValidationError>) {
    for issue in issues {
        validate_required_text("issue.issue_code", &issue.issue_code, errors);
        validate_required_text("issue.message", &issue.message, errors);
        if !matches!(issue.severity.as_str(), "info" | "warning" | "error") {
            errors.push(ValidationError::new(format!(
                "issue {} has unsupported severity {}",
                issue.issue_code, issue.severity
            )));
        }
    }
}

fn validate_report_status_coherence(report: &DeviceLinkReport, errors: &mut Vec<ValidationError>) {
    let blocking_failures = required_fail_count(
        &report.host_tools,
        |tool| tool.required,
        |tool| &tool.status,
    ) + required_fail_count(
        &report.tunnels,
        |tunnel| tunnel.required,
        |tunnel| &tunnel.status,
    ) + report
        .broker_endpoints
        .iter()
        .filter(|endpoint| endpoint.status == STATUS_FAIL)
        .count()
        + report
            .command_results
            .iter()
            .filter(|result| result.status == STATUS_FAIL)
            .count()
        + report
            .issues
            .iter()
            .filter(|issue| issue.severity == "error")
            .count();
    if report.status == STATUS_PASS && blocking_failures > 0 {
        errors.push(ValidationError::new(
            "device-link report status pass conflicts with blocking failures",
        ));
    }
    if report.status == STATUS_PASS && report.device_identity.adb_state != ADB_STATE_DEVICE {
        errors.push(ValidationError::new(
            "device-link report status pass requires adb_state=device",
        ));
    }
}

fn required_fail_count<T>(
    rows: &[T],
    required: impl Fn(&T) -> bool,
    status: impl Fn(&T) -> &String,
) -> usize {
    rows.iter()
        .filter(|row| required(row) && status(row).as_str() == STATUS_FAIL)
        .count()
}

fn validate_status(label: &str, value: &str, errors: &mut Vec<ValidationError>) {
    if !matches!(
        value,
        STATUS_PASS | STATUS_WARN | STATUS_FAIL | STATUS_SKIPPED
    ) {
        errors.push(ValidationError::new(format!(
            "{label} has unsupported status {value}"
        )));
    }
}

pub(crate) fn validate_required_text(label: &str, value: &str, errors: &mut Vec<ValidationError>) {
    if value.trim().is_empty() {
        errors.push(ValidationError::new(format!("{label} must not be empty")));
    }
}
