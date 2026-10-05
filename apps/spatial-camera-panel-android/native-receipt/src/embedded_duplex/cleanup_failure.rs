//! Bounded lifecycle diagnostics; these never supply Start or cleanup proof.
use rusty_quest_broker_authority::QuestBrokerRuntimeError;

#[derive(Clone, Copy)]
pub(super) enum Stage {
    Start,
    Media,
    Route,
}

pub(super) fn describe(stage: Stage, error: &QuestBrokerRuntimeError) -> String {
    use QuestBrokerRuntimeError::*;
    let prefix = match stage {
        Stage::Start => "full product Start incomplete",
        Stage::Media => "concurrent physical media cleanup pending",
        Stage::Route => "concurrent retained route cleanup pending",
    };
    // Broker Display can include arbitrary owner strings, JSON errors or IDs.
    // Emit only static variant codes and exact known static runtime messages.
    let (code, detail) = match error {
        NotInitialized => ("not_initialized", "cause_unavailable".to_owned()),
        TrustedMediaExecutorAbsent => ("executor_absent", "cause_unavailable".to_owned()),
        InvalidMediaExecutorGeneration => (
            "executor_generation_invalid",
            "cause_unavailable".to_owned(),
        ),
        MediaExecutionTicketInvalid => ("execution_ticket_invalid", "cause_unavailable".to_owned()),
        MediaPeerRuntimeConfig => ("peer_runtime_config", "cause_unavailable".to_owned()),
        MediaPeerRuntimeTransitionRejected => {
            ("peer_transition_rejected", "cause_unavailable".to_owned())
        }
        MediaPeerRuntime(_) => ("peer_runtime", "cause_unavailable".to_owned()),
        RuntimeLockPoisoned => ("runtime_lock_poisoned", "cause_unavailable".to_owned()),
        AdmissionProjection(_) => ("admission_projection", "cause_unavailable".to_owned()),
        // Every MediaStreamProductRuntimeError Display arm is a static message,
        // including its validation and serialization variants with payloads.
        MediaRuntime(cause) => ("media_runtime", cause.to_string()),
        MediaStopAttemptRetained(cause) => {
            ("media_stop_retained", safe_media_cause(cause).to_owned())
        }
        MediaStartAborted(cause) => ("media_start_aborted", safe_media_cause(cause).to_owned()),
        MediaStartAbortFailed {
            owner_error,
            abort_error,
        } => (
            "media_start_abort_retained",
            format!(
                "owner={};abort={}",
                safe_media_cause(owner_error),
                safe_media_cause(abort_error)
            ),
        ),
        Encode(_) => ("encode", "cause_unavailable".to_owned()),
        Decode(_) => ("decode", "cause_unavailable".to_owned()),
        _ => ("other_typed_error", "cause_unavailable".to_owned()),
    };
    format!("{prefix}: {code}: {}", bounded_detail(&detail))
}

fn bounded_detail(detail: &str) -> String {
    detail.chars().take(192).collect()
}

fn safe_media_cause(cause: &str) -> &'static str {
    // Failed-Start compensation retains the Broker error's Display, which
    // wraps the static media-runtime message once. Unwrap only that exact
    // owner prefix; the closed allowlist below still rejects arbitrary text.
    let cause = cause
        .strip_prefix("Quest media runtime failed: ")
        .unwrap_or(cause);
    match cause {
        "media owner provider execution/readback failed" => {
            "media owner provider execution/readback failed"
        }
        "media owner provider readback mismatch" => "media owner provider readback mismatch",
        "media owner provider family mismatch" => "media owner provider family mismatch",
        "media owner provider handle lifecycle mismatch" => {
            "media owner provider handle lifecycle mismatch"
        }
        "media owner completion mismatch" => "media owner completion mismatch",
        "media owner callback order mismatch" => "media owner callback order mismatch",
        "media owner callbacks incomplete" => "media owner callbacks incomplete",
        "media owner attempt is uncertain and requires compensation" => {
            "media owner attempt is uncertain and requires compensation"
        }
        "media stop has no uncertain owner attempt" => "media stop has no uncertain owner attempt",
        "media platform completion has no pending action" => {
            "media platform completion has no pending action"
        }
        "media operation invalid for runtime phase" => "media operation invalid for runtime phase",
        "media stop client/lease differs from active start authority" => {
            "media stop client/lease differs from active start authority"
        }
        "retained Manifold media-session subject is not current" => {
            "retained Manifold media-session subject is not current"
        }
        "media authority provider unavailable" => "media authority provider unavailable",
        "media authority epoch missing" => "media authority epoch missing",
        "media recovery journal unavailable" => "media recovery journal unavailable",
        "media platform completion binding mismatch" => {
            "media platform completion binding mismatch"
        }
        "media lifecycle rejected exact platform completion" => {
            "media lifecycle rejected exact platform completion"
        }
        "media cleanup completion order violated" => "media cleanup completion order violated",
        _ => "cause_unavailable",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cleanup_stage_and_static_cause_survive_without_owner_input() {
        let cause = QuestBrokerRuntimeError::MediaStopAttemptRetained(
            "media owner provider readback mismatch".to_owned(),
        );
        let media = describe(Stage::Media, &cause);
        let route = describe(Stage::Route, &cause);
        assert!(media.starts_with("concurrent physical media cleanup pending:"));
        assert!(route.starts_with("concurrent retained route cleanup pending:"));
        assert!(media.ends_with("media_stop_retained: media owner provider readback mismatch"));
        let secret = "{\"owner_input\":\"secret\"}".repeat(4096);
        let error = QuestBrokerRuntimeError::MediaStopAttemptRetained(secret.clone());
        let diagnostic = describe(Stage::Media, &error);
        assert!(diagnostic.ends_with("media_stop_retained: cause_unavailable"));
        assert!(!diagnostic.contains("owner_input"));
        assert!(diagnostic.len() < 256);
        assert!(
            matches!(error, QuestBrokerRuntimeError::MediaStopAttemptRetained(ref value) if value == &secret)
        );
        assert_eq!(
            bounded_detail(&"fixed diagnostic ".repeat(64))
                .chars()
                .count(),
            192
        );
        let start = describe(
            Stage::Start,
            &QuestBrokerRuntimeError::MediaStartAbortFailed {
                owner_error: "media owner provider readback mismatch".to_owned(),
                abort_error: secret,
            },
        );
        assert!(start.starts_with("full product Start incomplete: media_start_abort_retained:"));
        assert!(
            start.contains("owner=media owner provider readback mismatch;abort=cause_unavailable")
        );
        assert!(!start.contains("owner_input"));
    }

    #[test]
    fn actual_broker_abort_display_preserves_closed_media_cause() {
        use rusty_quest_media_stream::MediaStreamProductRuntimeError;
        for cause in [
            MediaStreamProductRuntimeError::OwnerProviderFailed,
            MediaStreamProductRuntimeError::OwnerReadbackMismatch,
        ] {
            // This is the same production Broker Display used by
            // compensate_failed_start, not an invented receipt string.
            let abort_error = QuestBrokerRuntimeError::MediaRuntime(cause).to_string();
            assert!(abort_error.starts_with("Quest media runtime failed: "));
            let retained = QuestBrokerRuntimeError::MediaStartAbortFailed {
                owner_error: "media owner provider execution/readback failed".to_owned(),
                abort_error: abort_error.clone(),
            };
            let diagnostic = describe(Stage::Start, &retained);
            assert!(!diagnostic.contains("cause_unavailable"));
            assert!(diagnostic.contains(&format!(
                ";abort={}",
                abort_error
                    .strip_prefix("Quest media runtime failed: ")
                    .unwrap()
            )));
            assert!(
                matches!(retained, QuestBrokerRuntimeError::MediaStartAbortFailed {
                abort_error: ref original, .. } if original == &abort_error)
            );
            assert!(diagnostic.len() < 256);
        }
    }

    #[test]
    fn broker_wrapper_does_not_widen_public_error_text() {
        for cause in [
            "Quest media runtime failed: secret_owner_data",
            "Quest media runtime failed: Quest media runtime failed: media owner provider execution/readback failed",
            "Quest media runtime failed: media owner provider execution/readback failed;token=secret",
            "Quest media runtime failed: media owner provider execution/readback failed\nsecret",
            " Quest media runtime failed: media owner provider execution/readback failed",
        ] {
            let retained = QuestBrokerRuntimeError::MediaStartAbortFailed {
                owner_error: "media owner provider readback mismatch".to_owned(),
                abort_error: cause.to_owned(),
            };
            let diagnostic = describe(Stage::Start, &retained);
            assert!(diagnostic.ends_with(";abort=cause_unavailable"));
            assert!(!diagnostic.contains("secret"));
            assert!(matches!(retained, QuestBrokerRuntimeError::MediaStartAbortFailed {
                abort_error: ref original, .. } if original == cause));
        }
    }

    #[test]
    fn unavailable_executor_stays_an_error_and_has_no_terminal_claim() {
        for stage in [Stage::Start, Stage::Media, Stage::Route] {
            let result: Result<(), String> =
                Err(QuestBrokerRuntimeError::TrustedMediaExecutorAbsent)
                    .map_err(|cause| describe(stage, &cause));
            let failure = result.expect_err("diagnostics cannot complete cleanup");
            assert!(failure.ends_with("executor_absent: cause_unavailable"));
            assert!(!failure.contains("terminal"));
        }
    }
}
