//! Closed diagnostic observations only. Never owner completion or cleanup evidence.
use std::sync::Mutex;

#[derive(Clone, Copy, Debug)]
pub(crate) enum Stage {
    Prepare,
    Dispatch,
    Executor,
    OrdinaryCallback,
    LocalProjection,
    LocalCallback,
    RemotePrepare,
    RemotePeerBind,
    RemoteRequesterAuthority,
    RemotePendingCapacity,
    RemoteSequence,
    RemoteEntropy,
    RemotePrepareEncode,
    RemotePrepareSign,
    RemotePrepareExchange,
    IncomingPrepare,
    IncomingRequesterAuthority,
    IncomingOriginalJoin,
    RemotePrepareProof,
    RemoteProjection,
    RemoteCommit,
    RemoteExchange,
    RemoteProof,
    SourceReadback,
    Capability,
}
impl Stage {
    pub(crate) fn code(self) -> &'static str {
        match self {
            Self::Prepare => "RETAINED_PREPARE",
            Self::Dispatch => "RETAINED_DISPATCH",
            Self::Executor => "RETAINED_EXECUTOR",
            Self::OrdinaryCallback => "RETAINED_ORDINARY_CALLBACK",
            Self::LocalProjection => "RETAINED_LOCAL_PROJECTION",
            Self::LocalCallback => "RETAINED_LOCAL_CALLBACK",
            Self::RemotePrepare => "RETAINED_REMOTE_PREPARE",
            Self::RemotePeerBind => "RETAINED_REMOTE_PEER_BIND",
            Self::RemoteRequesterAuthority => "RETAINED_REMOTE_REQUESTER_AUTHORITY",
            Self::RemotePendingCapacity => "RETAINED_REMOTE_PENDING_CAPACITY",
            Self::RemoteSequence => "RETAINED_REMOTE_SEQUENCE",
            Self::RemoteEntropy => "RETAINED_REMOTE_ENTROPY",
            Self::RemotePrepareEncode => "RETAINED_REMOTE_PREPARE_ENCODE",
            Self::RemotePrepareSign => "RETAINED_REMOTE_PREPARE_SIGN",
            Self::RemotePrepareExchange => "RETAINED_REMOTE_PREPARE_EXCHANGE",
            Self::IncomingPrepare => "RETAINED_INCOMING_PREPARE",
            Self::IncomingRequesterAuthority => "RETAINED_INCOMING_REQUESTER_AUTHORITY",
            Self::IncomingOriginalJoin => "RETAINED_INCOMING_ORIGINAL_JOIN",
            Self::RemotePrepareProof => "RETAINED_REMOTE_PREPARE_PROOF",
            Self::RemoteProjection => "RETAINED_REMOTE_PROJECTION",
            Self::RemoteCommit => "RETAINED_REMOTE_COMMIT",
            Self::RemoteExchange => "RETAINED_REMOTE_EXCHANGE",
            Self::RemoteProof => "RETAINED_REMOTE_PROOF",
            Self::SourceReadback => "RETAINED_SOURCE_READBACK",
            Self::Capability => "RETAINED_CAPABILITY",
        }
    }
}
/// Record first finite stage after the failing operation; return its original result unchanged.
pub(crate) fn observe<T>(
    result: Result<T, String>,
    slot: &Mutex<Option<&'static str>>,
    stage: Stage,
) -> Result<T, String> {
    if let Err(error) = &result {
        if let Ok(mut first) = slot.lock() {
            if first.is_none() {
                *first = Some(if matches!(stage, Stage::OrdinaryCallback) {
                    ordinary_cause(error).unwrap_or(stage.code())
                } else { stage.code() });
            }
        }
    }
    result
}
// Exact known errors only; arbitrary exception/payload text never becomes an
// observation. The original Result is returned unchanged and the first cause
// remains latched through subsequent compensation and cleanup failures.
fn ordinary_cause(error: &str) -> Option<&'static str> {
    Some(match error {
        "remote platform effect uncertain" => "ORDINARY_REMOTE_EFFECT_UNCERTAIN",
        "remote owner dispatch rejected" => "ORDINARY_REMOTE_DISPATCH_REJECTED",
        "verified owner effect mismatch" => "ORDINARY_VERIFIED_EFFECT_MISMATCH",
        "verified readback JSON is invalid" => "ORDINARY_READBACK_INVALID",
        "runtime busy" => "ORDINARY_RUNTIME_BUSY",
        "runtime slot poisoned" => "ORDINARY_RUNTIME_POISONED",
        "java_bridge.execute_call" | "java_bridge.execute_call.exception" => "ORDINARY_JAVA_CALLBACK_REJECTED",
        "java_bridge.attach" => "ORDINARY_JAVA_ATTACH_UNAVAILABLE",
        _ => return None,
    })
}
/// Preserve successful Java diagnostics; represent failure with a finite closed code.
/// No arbitrary exception text, identity, payload, ticket or path escapes this boundary.
pub(crate) fn diagnostic<T>(result: Result<T, String>) -> (Option<T>, &'static str) {
    match result {
        Ok(value) => (Some(value), "AVAILABLE"),
        Err(error) => (
            None,
            match error.as_str() {
                "native executor capability retired" | "native app record or lease changed" => {
                    "STALE_GENERATION"
                }
                "java_bridge.attach" => "ATTACH_UNAVAILABLE",
                "java_bridge.owner_diagnostic_call"
                | "java_bridge.owner_diagnostic_call.exception" => "JAVA_CALLBACK_REJECTED",
                "java_bridge.owner_diagnostic_call.exception_state" => {
                    "JAVA_EXCEPTION_STATE_UNAVAILABLE"
                }
                "java_bridge.owner_diagnostic_null" => "JAVA_DIAGNOSTIC_NULL",
                value if value.starts_with("java_bridge.owner_diagnostic_") => "DIAGNOSTIC_INVALID",
                _ => "UNAVAILABLE",
            },
        ),
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ordinary_cause_is_exact_closed_first_and_never_rewrites_result() {
        for error in ["remote platform effect uncertain", "remote owner dispatch rejected",
            "verified owner effect mismatch", "verified readback JSON is invalid",
            "runtime busy", "runtime slot poisoned", "java_bridge.execute_call",
            "java_bridge.execute_call.exception", "java_bridge.attach"] {
            let slot = Mutex::new(None);
            assert_eq!(observe::<()>(Err(error.into()), &slot, Stage::OrdinaryCallback), Err(error.into()));
            let first = ordinary_cause(error).unwrap();
            assert_eq!(*slot.lock().unwrap(), Some(first));
            assert!(observe::<()>(Err("later private cleanup".into()), &slot, Stage::SourceReadback).is_err());
            assert_eq!(*slot.lock().unwrap(), Some(first));
            assert_eq!(observe(Ok(7), &slot, Stage::OrdinaryCallback), Ok(7));
        }
        for error in ["private payload", "remote platform effect uncertain SECRET", ""] {
            let slot = Mutex::new(None);
            assert!(observe::<()>(Err(error.into()), &slot, Stage::OrdinaryCallback).is_err());
            assert_eq!(*slot.lock().unwrap(), Some("RETAINED_ORDINARY_CALLBACK"));
        }
        let slot = Mutex::new(None);
        assert!(observe::<()>(Err("remote platform effect uncertain".into()), &slot, Stage::RemoteProof).is_err());
        assert_eq!(*slot.lock().unwrap(), Some("RETAINED_REMOTE_PROOF"));
    }
    #[test]
    fn every_retained_boundary_preserves_original_error_and_no_completion() {
        for stage in [
            Stage::Prepare,
            Stage::Dispatch,
            Stage::Executor,
            Stage::OrdinaryCallback,
            Stage::LocalProjection,
            Stage::LocalCallback,
            Stage::RemotePrepare,
            Stage::RemotePeerBind,
            Stage::RemoteRequesterAuthority,
            Stage::RemotePendingCapacity,
            Stage::RemoteSequence,
            Stage::RemoteEntropy,
            Stage::RemotePrepareEncode,
            Stage::RemotePrepareSign,
            Stage::RemotePrepareExchange,
            Stage::IncomingPrepare,
            Stage::IncomingRequesterAuthority,
            Stage::IncomingOriginalJoin,
            Stage::RemotePrepareProof,
            Stage::RemoteProjection,
            Stage::RemoteCommit,
            Stage::RemoteExchange,
            Stage::RemoteProof,
            Stage::SourceReadback,
            Stage::Capability,
        ] {
            let slot = Mutex::new(None);
            let original = "private payload never copied to diagnostic".to_owned();
            let returned = observe::<()>(Err(original.clone()), &slot, stage);
            assert_eq!(returned, Err(original));
            assert_eq!(*slot.lock().unwrap(), Some(stage.code()));
        }
    }
    #[test]
    fn later_cleanup_failure_never_erases_first_or_turns_pending_into_success() {
        let slot = Mutex::new(None);
        assert!(observe::<()>(Err("original prepare".into()), &slot, Stage::Prepare).is_err());
        assert!(observe::<()>(Err("later proof".into()), &slot, Stage::RemoteProof).is_err());
        assert_eq!(*slot.lock().unwrap(), Some("RETAINED_PREPARE"));
        assert_eq!(observe(Ok(7), &slot, Stage::Dispatch), Ok(7));
        assert_eq!(*slot.lock().unwrap(), Some("RETAINED_PREPARE"));
    }
    #[test]
    fn unavailable_attachment_java_exception_and_stale_generation_are_explicit() {
        for (error, code) in [
            ("java_bridge.attach", "ATTACH_UNAVAILABLE"),
            (
                "java_bridge.owner_diagnostic_call.exception",
                "JAVA_CALLBACK_REJECTED",
            ),
            ("native executor capability retired", "STALE_GENERATION"),
            ("native app record or lease changed", "STALE_GENERATION"),
            ("java_bridge.owner_diagnostic_bounds", "DIAGNOSTIC_INVALID"),
            ("private raw exception secret", "UNAVAILABLE"),
        ] {
            assert_eq!(diagnostic::<()>(Err(error.into())), (None, code));
        }
        assert_eq!(diagnostic(Ok(9)), (Some(9), "AVAILABLE"));
    }
    #[test]
    fn remote_prepare_substages_are_finite_and_later_outer_errors_do_not_replace_them() {
        for (stage, code) in [
            (Stage::RemotePeerBind, "RETAINED_REMOTE_PEER_BIND"),
            (
                Stage::RemoteRequesterAuthority,
                "RETAINED_REMOTE_REQUESTER_AUTHORITY",
            ),
            (
                Stage::RemotePendingCapacity,
                "RETAINED_REMOTE_PENDING_CAPACITY",
            ),
            (Stage::RemoteSequence, "RETAINED_REMOTE_SEQUENCE"),
            (Stage::RemoteEntropy, "RETAINED_REMOTE_ENTROPY"),
            (Stage::RemotePrepareEncode, "RETAINED_REMOTE_PREPARE_ENCODE"),
            (Stage::RemotePrepareSign, "RETAINED_REMOTE_PREPARE_SIGN"),
            (
                Stage::RemotePrepareExchange,
                "RETAINED_REMOTE_PREPARE_EXCHANGE",
            ),
        ] {
            let slot = Mutex::new(None);
            // Successful preparation work cannot manufacture a failure or completion.
            assert_eq!(observe(Ok(7), &slot, stage), Ok(7));
            assert_eq!(*slot.lock().unwrap(), None);
            let private_error = "java_bridge.exchange: private remote payload".to_owned();
            assert_eq!(
                observe::<()>(Err(private_error.clone()), &slot, stage),
                Err(private_error)
            );
            assert_eq!(*slot.lock().unwrap(), Some(code));
            assert!(observe::<()>(
                Err("outer retained prepare".into()),
                &slot,
                Stage::RemotePrepare
            )
            .is_err());
            assert!(observe::<()>(
                Err("later remote proof".into()),
                &slot,
                Stage::RemotePrepareProof
            )
            .is_err());
            assert_eq!(*slot.lock().unwrap(), Some(code));
            assert!(!code.contains("java_bridge") && !code.contains("payload"));
        }
    }
}
