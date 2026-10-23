//! Failure-only capture at exact owner boundaries; never changes dispatch results.
use crate::{AndroidMediaExecutionMode, AndroidMediaExecutionTicket};
use rusty_quest_media_stream::{
    DiagnosticCleanupState, DiagnosticEffectState, DiagnosticLayer, DiagnosticOperation,
    DiagnosticPhase, FailureInput, MediaStreamPlatformOperation, PrivateFailureChunk,
    SafeDiagnosticValue,
};
use sha2::{Digest, Sha256};

pub(crate) fn phase(
    ticket: &AndroidMediaExecutionTicket,
    mode: AndroidMediaExecutionMode,
) -> DiagnosticPhase {
    if mode == AndroidMediaExecutionMode::CompensateUncertain {
        DiagnosticPhase::Compensation
    } else if ticket.operation == MediaStreamPlatformOperation::Stop {
        DiagnosticPhase::Cleanup
    } else {
        DiagnosticPhase::Primary
    }
}

pub(crate) fn observe_error(
    operation: Option<&DiagnosticOperation>,
    ticket: &AndroidMediaExecutionTicket,
    mode: AndroidMediaExecutionMode,
    boundary: &'static str,
    category: &'static str,
    error: &str,
    request: Option<&[u8]>,
    response: Option<&[u8]>,
    public_key: Option<&[u8]>,
) {
    observe_error_with_context(
        operation,
        ticket,
        mode,
        boundary,
        category,
        error,
        request,
        response,
        public_key,
        &[],
    );
}

pub(crate) fn observe_error_with_context(
    operation: Option<&DiagnosticOperation>,
    ticket: &AndroidMediaExecutionTicket,
    mode: AndroidMediaExecutionMode,
    boundary: &'static str,
    category: &'static str,
    error: &str,
    request: Option<&[u8]>,
    response: Option<&[u8]>,
    public_key: Option<&[u8]>,
    extra: &[PrivateFailureChunk<'_>],
) {
    let Some(operation) = operation else { return };
    let mut correlation = operation.correlation();
    correlation.native_generation = Some(ticket.executor_generation);
    correlation.attempt_sequence = Some(u64::from(ticket.sequence));
    correlation.request_sha256 = request
        .map(|bytes| format!("{:x}", Sha256::digest(bytes)))
        .or(correlation.request_sha256);
    let operation = operation.clone().with_correlation(correlation);
    let ticket_bytes = serde_json::to_vec(ticket).unwrap_or_default();
    let mut chunks = vec![
        PrivateFailureChunk {
            name: "error",
            bytes: error.as_bytes(),
        },
        PrivateFailureChunk {
            name: "source_ticket",
            bytes: &ticket_bytes,
        },
    ];
    if let Some(bytes) = request {
        chunks.push(PrivateFailureChunk {
            name: "signed_request_frame",
            bytes,
        });
    }
    if let Some(bytes) = response {
        chunks.push(PrivateFailureChunk {
            name: "signed_response_frame",
            bytes,
        });
    }
    if let Some(bytes) = public_key {
        chunks.push(PrivateFailureChunk {
            name: "enrolled_target_public_key",
            bytes,
        });
    }
    chunks.extend(extra.iter().map(|chunk| PrivateFailureChunk {
        name: chunk.name,
        bytes: chunk.bytes,
    }));
    let before_effect = matches!(
        boundary,
        "ticket_generation"
            | "owner_placement"
            | "clock_read"
            | "current_route_projection"
            | "prepare_request"
            | "request_frame_encode"
            | "target_request_shape"
            | "target_replay_identity"
            | "target_clock_read"
            | "target_request_freshness"
            | "target_replay_capacity"
            | "target_request_signing_bytes"
            | "target_request_signature_decode"
            | "target_current_authority_verify"
            | "target_executor_generation"
            | "target_binding_key"
            | "target_binding_derive"
            | "target_pending_persistence"
    );
    operation.record(
        FailureInput::new(
            phase(ticket, mode),
            DiagnosticLayer::Rust,
            "owner_dispatch",
            boundary,
            category,
            "unclassified",
        )
        .states(
            if before_effect {
                DiagnosticEffectState::NotEntered
            } else {
                DiagnosticEffectState::Unknown
            },
            if phase(ticket, mode) == DiagnosticPhase::Primary {
                DiagnosticCleanupState::NotRequested
            } else {
                DiagnosticCleanupState::Unknown
            },
        )
        .safe_detail(
            "ticket_sequence",
            SafeDiagnosticValue::Integer(i64::from(ticket.sequence)),
        )
        .private_context(chunks),
    );
}

pub(crate) fn observed<T>(
    operation: Option<&DiagnosticOperation>,
    ticket: &AndroidMediaExecutionTicket,
    mode: AndroidMediaExecutionMode,
    boundary: &'static str,
    category: &'static str,
    result: Result<T, String>,
) -> Result<T, String> {
    if let Err(error) = &result {
        observe_error(
            operation, ticket, mode, boundary, category, error, None, None, None,
        );
    }
    result
}
