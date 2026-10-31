//! Acquisition readiness only. Composition publishes selection, accepted raw ACC
//! publishes native receipt time, and the session worker owns all control effects.
//! This owner never calls composition, session, classification or history code.

use std::{
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};

pub(crate) const TIMEOUT: Duration = Duration::from_secs(1);

#[derive(Default)]
struct Acquisition {
    owner: u64,
    selection_revision: u64,
    required: bool,
    last_sample: Option<Instant>,
    interruption_revision: u64,
    interrupted_at: Option<Instant>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct Status {
    pub(crate) required: bool,
    pub(crate) fresh: bool,
    pub(crate) pause_latched: bool,
    pub(crate) reason: &'static str,
}

impl Status {
    pub(crate) fn json(self) -> serde_json::Value {
        serde_json::json!({"required":self.required,"fresh":self.fresh,
            "pause_latched":self.pause_latched,"reason":self.reason,"timeout_ms":1000})
    }
}

fn acquisition() -> &'static Mutex<Acquisition> {
    static STATE: OnceLock<Mutex<Acquisition>> = OnceLock::new();
    STATE.get_or_init(|| Mutex::new(Acquisition::default()))
}

pub(crate) fn configure(owner: u64, required: bool) {
    let mut state = acquisition().lock().unwrap_or_else(|e| e.into_inner());
    if state.owner != owner || state.required != required {
        if state.required || required {
            state.interruption_revision = state.interruption_revision.saturating_add(1);
            state.interrupted_at.get_or_insert_with(Instant::now);
        }
        state.selection_revision = state.selection_revision.saturating_add(1);
        state.owner = owner;
        state.required = required;
        state.last_sample = None;
    }
}

/// Captured at native ingress entry, before any acceptance or publication.
#[derive(Clone, Copy)]
pub(crate) struct AcquisitionToken {
    owner: u64,
    selection_revision: u64,
    received_at: Instant,
}

pub(crate) fn acquisition_token() -> AcquisitionToken {
    let state = acquisition().lock().unwrap_or_else(|e| e.into_inner());
    AcquisitionToken {
        owner: state.owner,
        selection_revision: state.selection_revision,
        received_at: Instant::now(),
    }
}

/// Health -> ingress is the only nested lock direction. Configuration cannot
/// replace the owner between raw acceptance and its matching health receipt.
/// A stale token never runs the acceptance closure or changes the ingress.
pub(crate) fn commit_acc(token: AcquisitionToken, accept: impl FnOnce() -> bool) -> bool {
    let mut state = acquisition().lock().unwrap_or_else(|e| e.into_inner());
    if state.owner != token.owner || state.selection_revision != token.selection_revision {
        return false;
    }
    if !accept() {
        return false;
    }
    if state.required && state.last_sample.is_none_or(|at| token.received_at >= at) {
        if let Some(previous) = state
            .last_sample
            .filter(|at| token.received_at.duration_since(*at) > TIMEOUT)
        {
            state.interruption_revision = state.interruption_revision.saturating_add(1);
            state.interrupted_at.get_or_insert(previous + TIMEOUT);
        }
        state.last_sample = Some(token.received_at);
    }
    true
}

pub(crate) struct LossObservation {
    pub(crate) revision: u64,
    pub(crate) overdue: Duration,
}

// Only an accepted explicit session control acknowledges prior interruptions.
// Recovery keeps the earliest lost-input deadline until that acknowledgement.
pub(crate) fn acknowledge_interruptions() -> u64 {
    let mut state = acquisition().lock().unwrap_or_else(|e| e.into_inner());
    state.interrupted_at = None;
    state.interruption_revision
}

pub(crate) fn status(
    recording_requires_polar: bool,
    pause_latched: bool,
) -> (Status, LossObservation) {
    let state = acquisition().lock().unwrap_or_else(|e| e.into_inner());
    state.status(Instant::now(), recording_requires_polar, pause_latched)
}

impl Acquisition {
    fn status(
        &self,
        now: Instant,
        latched_required: bool,
        pause_latched: bool,
    ) -> (Status, LossObservation) {
        let required = self.required || latched_required;
        let age = self
            .last_sample
            .and_then(|at| now.checked_duration_since(at));
        let fresh = self.required && age.is_some_and(|age| age <= TIMEOUT);
        let reason = if !required {
            "not-required"
        } else if !self.required {
            "source-unavailable"
        } else if fresh && pause_latched {
            "recovered-awaiting-resume"
        } else if fresh {
            "fresh"
        } else if self.last_sample.is_none() {
            "awaiting-acc"
        } else {
            "acc-stale"
        };
        (
            Status {
                required,
                fresh,
                pause_latched,
                reason,
            },
            LossObservation {
                revision: self.interruption_revision,
                overdue: self
                    .interrupted_at
                    .and_then(|at| now.checked_duration_since(at))
                    .unwrap_or_else(|| age.unwrap_or_default().saturating_sub(TIMEOUT)),
            },
        )
    }
}

#[cfg(test)]
pub(crate) mod test_support {
    use super::*;

    pub(crate) fn age_sample(age: Duration) {
        let mut state = acquisition().lock().unwrap();
        assert!(
            state.last_sample.is_some(),
            "test requires accepted raw ACC"
        );
        state.last_sample = Some(Instant::now() - age);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn source_round_trip_retires_token_and_current_commit_holds_configuration_lock() {
        let _binding = crate::breath_composition_runtime::scoped_test_binding::install_polar();
        let stale = acquisition_token();
        configure(stale.owner, false);
        configure(stale.owner, true);
        let mut stale_writes = 0;
        assert!(!commit_acc(stale, || {
            stale_writes += 1;
            true
        }));
        assert_eq!(stale_writes, 0);
        assert!(!status(false, false).0.fresh);
        let current = acquisition_token();
        assert!(commit_acc(current, || {
            // Raw acceptance cannot race configuration in the commit gap.
            assert!(matches!(
                acquisition().try_lock(),
                Err(std::sync::TryLockError::WouldBlock)
            ));
            true
        }));
        assert!(status(false, false).0.fresh);
    }

    #[test]
    fn freshness_is_bounded_by_native_receipt_time_and_exact_one_second_threshold() {
        let now = Instant::now();
        let state = Acquisition {
            owner: 1,
            required: true,
            last_sample: Some(now),
            ..Acquisition::default()
        };
        assert!(
            state
                .status(now + Duration::from_secs(1), false, false)
                .0
                .fresh
        );
        let (stale, overdue) =
            state.status(now + Duration::from_nanos(1_000_000_001), false, false);
        assert!(!stale.fresh);
        assert_eq!(stale.reason, "acc-stale");
        assert_eq!(overdue.overdue, Duration::from_nanos(1));
        assert!(
            !state
                .status(now - Duration::from_millis(1), false, false)
                .0
                .fresh
        );
    }
}
