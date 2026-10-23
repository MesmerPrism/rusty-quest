//! Render-thread ownership of the optional pure phase module; the app keeps its clock.
use crate::{
    feedback::{FeedbackHost, Snapshot},
    strength_cycle_adapter::{self, StepOutcome, StepSource},
};
use std::{
    cell::{Cell, RefCell},
    path::Path,
};

thread_local! {
    static HOST: RefCell<Option<FeedbackHost>> = const { RefCell::new(None) };
    static LAST: Cell<StepOutcome> = const { Cell::new(StepOutcome {phase:0.0, source:StepSource::NoHost}) };
    static ENABLED: Cell<bool> = const { Cell::new(false) };
    static GENERATION: Cell<u64> = const { Cell::new(0) };
    static CALLS: Cell<u64> = const { Cell::new(0) };
    static ADOPTED: Cell<u64> = const { Cell::new(0) };
}

pub(crate) fn set_enabled(enabled: bool) {
    ENABLED.with(|value| value.set(enabled));
}

/// Called by the admitted render owner before taking the phase clock lock.
pub(crate) fn activate(
    path: &Path,
    sha256: &str,
    generation: u64,
    probe: i64,
) -> Result<Snapshot, String> {
    HOST.with(|slot| {
        let mut slot = slot.borrow_mut();
        let result = slot
            .get_or_insert_with(FeedbackHost::new)
            .activate_immutable(path, sha256, generation, probe)
            .map_err(|e| e.to_string());
        if let Ok(snapshot) = &result {
            GENERATION.with(|value| value.set(snapshot.generation));
        }
        result
    })
}

/// Pure evaluation only: no file reads, activation, callbacks, waits or cross-thread handles.
pub(crate) fn step(phase: f32, delta: f32) -> f32 {
    let enabled = ENABLED.with(Cell::get);
    let outcome = HOST.with(|slot| {
        let mut slot = slot.borrow_mut();
        strength_cycle_adapter::step_outcome(
            if enabled { slot.as_mut() } else { None },
            phase,
            delta,
        )
    });
    // ABI completion is not semantic adoption. Refusals use the original expression.
    let adopted = outcome.source == StepSource::Module
        && outcome.phase.is_finite()
        && (0.0..1.0).contains(&outcome.phase);
    let effective = if adopted {
        outcome.phase
    } else {
        (phase + delta).rem_euclid(1.0)
    };
    LAST.with(|last| {
        last.set(StepOutcome {
            phase: effective,
            source: outcome.source,
        })
    });
    CALLS.with(|value| value.set(value.get().saturating_add(1)));
    ADOPTED.with(|value| {
        value.set(if adopted {
            GENERATION.with(Cell::get)
        } else {
            0
        })
    });
    effective
}

pub(crate) fn last_outcome() -> StepOutcome {
    LAST.with(Cell::get)
}
pub(crate) fn phase_call_count() -> u64 {
    CALLS.with(Cell::get)
}
pub(crate) fn adopted_module_generation() -> u64 {
    ADOPTED.with(Cell::get)
}
pub(crate) fn snapshot() -> Option<Snapshot> {
    HOST.with(|slot| slot.borrow().as_ref().and_then(|h| h.snapshot().ok()))
}
