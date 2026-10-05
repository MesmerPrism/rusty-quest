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
    if result.is_err() {
        if let Ok(mut first) = slot.lock() {
            if first.is_none() {
                *first = Some(stage.code());
            }
        }
    }
    result
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
    fn every_retained_boundary_preserves_original_error_and_no_completion() {
        for stage in [
            Stage::Prepare,
            Stage::Dispatch,
            Stage::Executor,
            Stage::OrdinaryCallback,
            Stage::LocalProjection,
            Stage::LocalCallback,
            Stage::RemotePrepare,
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
}
