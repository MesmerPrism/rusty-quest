//! Diagnostic projection only; never owner-effect, cleanup or readiness evidence.
use serde_json::{json, Value};
fn closed(key: &str, value: &str) -> bool {
    match key {
        "stage" => matches!(value, "NONE"|"CALLBACK_FENCE"|"PROJECTION_BINDING"|"REGISTRY_BINDING"|"INCOMING_FENCE"|"LOCAL_QUIESCENCE"|"PROVIDER_EXECUTION"|"RECEIPT_VERIFICATION"|"INCOMING_ARM_VERIFICATION"),
        "sink_stage" => matches!(value, "NONE"|"PEER_PROJECTION"|"READER_STAGE"|"READER_IDENTITY"|"RECEIVER_CREATE"|"PROVIDER_GETTER"|"PEER_BIND"|"RECEIVER_EFFECT"),
        "action" => matches!(value, "NONE"|"ARM_RECEIVER"|"ARM_CLEANUP"|"START"|"STOP"|"CLEANUP"|"BEFORE_TICKET"),
        "code" => matches!(value, "NONE"|"OWNER_EFFECT_REJECTED"),
        "owner" => matches!(value, "NONE"|"source"|"processor"|"route"|"socket"|"codec"|"cleanup"|"sink"),
        "cause" => matches!(value, "NONE"|"CODEC"|"TIMEOUT"|"IO"|"SECURITY"|"ARGUMENT"|"STATE"|"OTHER"),
        "provider_reason" => matches!(value, "INCOMING_ARM_ORDER"|"INCOMING_ARM_PROJECTION"|"INCOMING_ARM_EVIDENCE"|"INCOMING_ARM_UNAVAILABLE"|"NONE"|"TICKET_PARSE"|"STALE_GENERATION"|"UNDECLARED_BINDING"|"REGISTRY_CLOSED"|"PROVIDER_BUSY"|"CAPACITY"|"PREPARATION_ALREADY_ATTEMPTED"|"FOREIGN_READBACK"|"RECEIPT_COLLISION"|"DISPLAY_LOCAL_SHUTDOWN"|"DISPLAY_DISPATCH_FENCED"|"DISPLAY_TRANSITION_TIMEOUT"|"DISPLAY_ADMISSION_REJECTED"|"DISPLAY_NATIVE_ACTIVE_EPOCH"|"DISPLAY_NATIVE_ACTIVE_STATE"|"DISPLAY_NATIVE_BOUNDS"|"DISPLAY_NATIVE_CAPTURE"|"DISPLAY_NATIVE_CARRIER"|"DISPLAY_NATIVE_CLOCK"|"DISPLAY_NATIVE_FRAME_ABSENT"|"DISPLAY_NATIVE_FRAME_EPOCH"|"DISPLAY_NATIVE_FRAME_FUTURE"|"DISPLAY_NATIVE_FRAME_STALE"|"DISPLAY_NATIVE_INPUT"|"DISPLAY_NATIVE_LOCAL"|"DISPLAY_NATIVE_PROCESS_EPOCH"|"DISPLAY_NATIVE_SOURCE_STATE"|"DISPLAY_NATIVE_SUPERSEDED"|"DISPLAY_OWN_CAPTURE_STATE"|"DISPLAY_OWN_CAPTURE_FRESH"|"DISPLAY_OWN_CARRIER_SUPERSEDED"|"DISPLAY_NATIVE_SHAPE"|"DISPLAY_ROUTING_SUPERSEDED"|"OTHER"),
        _ => false,
    }
}
fn projection(text: &str) -> Option<Value> {
    if text.len() > 256 { return None; }
    let value: Value = serde_json::from_str(text).ok()?;
    let fields = value.as_object()?;
    if fields.len() != 7 || !fields.iter().all(|(key, value)| value.as_str().is_some_and(|v| closed(key, v))) { return None; }
    Some(value)
}
fn code(error: &str) -> &'static str {
    match error {
        "java_bridge.owner_diagnostic_bounds" => "java_bridge.owner_diagnostic_bounds",
        "java_bridge.owner_diagnostic_json" => "java_bridge.owner_diagnostic_json",
        "java_bridge.owner_diagnostic_stage" => "java_bridge.owner_diagnostic_stage",
        "java_bridge.owner_diagnostic_sink" => "java_bridge.owner_diagnostic_sink",
        "java_bridge.owner_diagnostic_action" => "java_bridge.owner_diagnostic_action",
        "java_bridge.owner_diagnostic_code" => "java_bridge.owner_diagnostic_code",
        "java_bridge.owner_diagnostic_reason" => "java_bridge.owner_diagnostic_reason",
        "java_bridge.owner_diagnostic_owner" => "java_bridge.owner_diagnostic_owner",
        "java_bridge.owner_diagnostic_cause" => "java_bridge.owner_diagnostic_cause",
        "java_bridge.owner_diagnostic_closed_values" => "java_bridge.owner_diagnostic_closed_values",
        "java_bridge.owner_diagnostic_call" => "java_bridge.owner_diagnostic_call",
        "java_bridge.owner_diagnostic_type" => "java_bridge.owner_diagnostic_type",
        "java_bridge.owner_diagnostic_null" => "java_bridge.owner_diagnostic_null",
        "java_bridge.owner_diagnostic_string" => "java_bridge.owner_diagnostic_string",
        "java_bridge.owner_diagnostic_call.exception" => "java_bridge.owner_diagnostic_call.exception",
        "java_bridge.owner_diagnostic_call.exception_state" => "java_bridge.owner_diagnostic_call.exception_state",
        "java_bridge.attach" => "java_bridge.attach",
        _ => "UNAVAILABLE",
    }
}
pub(super) fn snapshot(text: &str, parsed: Result<Value, String>) -> Value {
    let (status, error) = match parsed { Ok(_) => ("AVAILABLE", None), Err(error) => ("REJECTED", Some(code(&error))) };
    json!({"schema":"rusty.quest.embedded_duplex.owner_diagnostic_native_read.v1",
        "qualification_claimed":false,"strict_parser_status":status,"strict_parser_error":error,
        "closed_java_fields":projection(text),"java_text_bytes":text.len()})
}
pub(super) fn unavailable(error: &str) -> Value {
    json!({"schema":"rusty.quest.embedded_duplex.owner_diagnostic_native_read.v1",
        "qualification_claimed":false,"strict_parser_status":"UNAVAILABLE","strict_parser_error":code(error),
        "closed_java_fields":null,"java_text_bytes":null})
}
