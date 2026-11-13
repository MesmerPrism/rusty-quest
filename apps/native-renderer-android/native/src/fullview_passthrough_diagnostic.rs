//! Explicit diagnostic selection. Unselected native products retain their own renderer policy.
pub(crate) fn select(
    selected: bool,
    showcase: bool,
    session_owner: bool,
    camera: bool,
    passthrough_style_only: bool,
) -> Result<bool, &'static str> {
    if !selected {
        return Ok(false);
    }
    if showcase || session_owner || camera || !passthrough_style_only {
        return Err(
            "Meta diagnostic requires its isolated passthrough-only product without camera or session owner",
        );
    }
    Ok(true)
}

/// Production dispatch. The Android caller supplies only the cfg-selected entry body.
pub(crate) fn run_entry<T, R>(
    diagnostic: bool,
    showcase: bool,
    app: T,
    product: impl FnOnce(T) -> R,
    isolated: impl FnOnce(T) -> R,
) -> Result<R, &'static str> {
    if diagnostic && showcase {
        return Err("Showcase cannot select Meta diagnostic composition");
    }
    Ok(if diagnostic {
        isolated(app)
    } else {
        product(app)
    })
}
/// One-use native-thread ticket. Only the real renderer result plus completed
/// app receipt can authorize review; returning from a thread is not quiescence.
pub(crate) struct DiagnosticOwner {
    decision: Option<bool>,
}
impl DiagnosticOwner {
    pub(crate) fn new() -> Self {
        Self { decision: None }
    }
    pub(crate) fn finish(&mut self, renderer_ok: bool, app_receipt_ready: bool) -> bool {
        if self.decision.is_some() {
            return false;
        }
        let ready = renderer_ok && app_receipt_ready;
        self.decision = Some(ready);
        ready
    }
    pub(crate) fn can_review(&self) -> bool {
        self.decision == Some(true)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn disabled_diagnostic_never_restricts_ordinary_renderers_or_showcase() {
        for showcase in [false, true] {
            for session in [false, true] {
                for camera in [false, true] {
                    for passthrough in [false, true] {
                        assert_eq!(
                            select(false, showcase, session, camera, passthrough),
                            Ok(false)
                        );
                    }
                }
            }
        }
    }
    #[test]
    fn selected_diagnostic_is_isolated_from_other_product_owners() {
        assert_eq!(select(true, false, false, false, true), Ok(true));
        for case in [
            (true, false, false, true),
            (false, true, false, true),
            (false, false, true, true),
            (false, false, false, false),
        ] {
            assert!(select(true, case.0, case.1, case.2, case.3).is_err());
        }
    }

    #[test]
    fn actual_entry_dispatch_never_initializes_or_captures_an_experiment_for_diagnostic() {
        use std::cell::Cell;
        for (diagnostic, showcase) in [(false, false), (false, true), (true, false)] {
            let init = Cell::new(0);
            let capture = Cell::new(0);
            let render = Cell::new(0);
            run_entry(
                diagnostic,
                showcase,
                (),
                |()| {
                    if !showcase {
                        init.set(init.get() + 1);
                        capture.set(capture.get() + 1);
                    }
                },
                |()| {
                    render.set(render.get() + 1);
                },
            )
            .unwrap();
            assert_eq!(init.get(), i32::from(!diagnostic && !showcase));
            assert_eq!(capture.get(), i32::from(!diagnostic && !showcase));
            assert_eq!(render.get(), i32::from(diagnostic));
        }
        assert!(
            run_entry(
                true,
                true,
                (),
                |()| panic!("incompatible product ran"),
                |()| panic!("incompatible diagnostic ran")
            )
            .is_err()
        );
    }
    #[test]
    fn diagnostic_owner_is_invalid_until_renderer_and_actual_app_receipt_complete() {
        let mut owner = DiagnosticOwner::new();
        assert!(!owner.can_review());
        assert!(owner.finish(true, true));
        assert!(owner.can_review());
        for (renderer, receipt) in [(false, false), (false, true), (true, false)] {
            let mut owner = DiagnosticOwner::new();
            assert!(!owner.finish(renderer, receipt));
            assert!(!owner.can_review());
            assert!(
                !owner.finish(true, true),
                "failure cannot be retried or turned into quiescence"
            );
        }
    }
}
