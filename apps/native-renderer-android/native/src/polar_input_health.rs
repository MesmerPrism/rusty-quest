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
    interrupted_reason: Option<&'static str>,
    ingress_count: u64,
    accepted_count: u64,
    rejected_count: u64,
    last_ingress: Option<Instant>,
    last_commit: Option<Instant>,
    ingress_to_commit_ms: Option<u64>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct Status {
    pub(crate) required: bool,
    pub(crate) fresh: bool,
    pub(crate) pause_latched: bool,
    pub(crate) reason: &'static str,
    accepted_age_ms: Option<u64>,
    ingress_age_ms: Option<u64>,
    commit_age_ms: Option<u64>,
    ingress_to_commit_ms: Option<u64>,
    ingress_count: u64,
    accepted_count: u64,
    rejected_count: u64,
}

impl Status {
    /// Diagnostic time passing must not churn the session controller projection.
    pub(crate) fn same_readiness(self, other: Self) -> bool {
        (self.required, self.fresh, self.pause_latched, self.reason)
            == (
                other.required,
                other.fresh,
                other.pause_latched,
                other.reason,
            )
    }

    pub(crate) fn json(self) -> serde_json::Value {
        serde_json::json!({"required":self.required,"fresh":self.fresh,
            "pause_latched":self.pause_latched,"reason":self.reason,"timeout_ms":1000,
            "clock":"rust-instant","accepted_age_ms":self.accepted_age_ms,
            "ingress_age_ms":self.ingress_age_ms,"commit_age_ms":self.commit_age_ms,
            "ingress_to_commit_ms":self.ingress_to_commit_ms,"ingress_count":self.ingress_count,
            "accepted_count":self.accepted_count,"rejected_count":self.rejected_count})
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
            if state.interrupted_at.is_none() {
                state.interrupted_at = Some(Instant::now());
                state.interrupted_reason = Some("source-unavailable");
            }
        }
        state.selection_revision = state.selection_revision.saturating_add(1);
        state.owner = owner;
        state.required = required;
        state.last_sample = None;
        state.ingress_count = 0;
        state.accepted_count = 0;
        state.rejected_count = 0;
        state.last_ingress = None;
        state.last_commit = None;
        state.ingress_to_commit_ms = None;
    }
}

/// Acceptance token retains its existing receipt clock; entry is a separate diagnostic witness.
#[derive(Clone, Copy)]
pub(crate) struct AcquisitionToken {
    owner: u64,
    selection_revision: u64,
    received_at: Instant,
    entered_at: Instant,
}

pub(crate) fn acquisition_token() -> AcquisitionToken {
    acquisition_token_entered(Instant::now())
}

pub(crate) fn acquisition_token_entered(entered_at: Instant) -> AcquisitionToken {
    let mut state = acquisition().lock().unwrap_or_else(|e| e.into_inner());
    // Keep the acceptance witness at the original point immediately after this lock.
    let received_at = Instant::now();
    state.ingress_count = state.ingress_count.saturating_add(1);
    state.last_ingress = Some(entered_at);
    AcquisitionToken {
        owner: state.owner,
        selection_revision: state.selection_revision,
        received_at,
        entered_at,
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
        state.rejected_count = state.rejected_count.saturating_add(1);
        return false;
    }
    let committed_at = Instant::now();
    state.accepted_count = state.accepted_count.saturating_add(1);
    state.last_commit = Some(committed_at);
    state.ingress_to_commit_ms = committed_at
        .checked_duration_since(token.entered_at)
        .map(duration_ms);
    if state.required && state.last_sample.is_none_or(|at| token.received_at >= at) {
        if let Some(previous) = state
            .last_sample
            .filter(|at| token.received_at.duration_since(*at) > TIMEOUT)
        {
            state.interruption_revision = state.interruption_revision.saturating_add(1);
            if state.interrupted_at.is_none() {
                state.interrupted_at = Some(previous + TIMEOUT);
                state.interrupted_reason = Some("acc-stale");
            }
        }
        state.last_sample = Some(token.received_at);
    }
    true
}

pub(crate) struct LossObservation {
    pub(crate) revision: u64,
    pub(crate) overdue: Duration,
    pub(crate) reason: &'static str,
}

// Only an accepted explicit session control acknowledges prior interruptions.
// Recovery keeps the earliest lost-input deadline until that acknowledgement.
pub(crate) fn acknowledge_interruptions() -> u64 {
    let mut state = acquisition().lock().unwrap_or_else(|e| e.into_inner());
    state.interrupted_at = None;
    state.interrupted_reason = None;
    state.interruption_revision
}

pub(crate) fn status(
    recording_requires_polar: bool,
    pause_latched: bool,
) -> (Status, LossObservation) {
    let state = acquisition().lock().unwrap_or_else(|e| e.into_inner());
    state.status(Instant::now(), recording_requires_polar, pause_latched)
}

fn duration_ms(duration: Duration) -> u64 {
    duration.as_millis().min(u128::from(u64::MAX)) as u64
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
                accepted_age_ms: age.map(duration_ms),
                ingress_age_ms: self
                    .last_ingress
                    .and_then(|at| now.checked_duration_since(at))
                    .map(duration_ms),
                commit_age_ms: self
                    .last_commit
                    .and_then(|at| now.checked_duration_since(at))
                    .map(duration_ms),
                ingress_to_commit_ms: self.ingress_to_commit_ms,
                ingress_count: self.ingress_count,
                accepted_count: self.accepted_count,
                rejected_count: self.rejected_count,
            },
            LossObservation {
                revision: self.interruption_revision,
                reason: self.interrupted_reason.unwrap_or(reason),
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
        let before = status(false, false).0.json();
        assert_eq!(before["ingress_count"], 0);
        assert_eq!(before["accepted_count"], 0);
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
        let observed = status(false, false).0.json();
        assert_eq!(
            observed["ingress_count"].as_u64().unwrap(),
            before["ingress_count"].as_u64().unwrap() + 1
        );
        assert_eq!(
            observed["accepted_count"].as_u64().unwrap(),
            before["accepted_count"].as_u64().unwrap() + 1
        );
        let denied = acquisition_token();
        assert!(!commit_acc(denied, || false));
        let rejected = status(false, false).0.json();
        assert_eq!(rejected["accepted_count"], observed["accepted_count"]);
        assert_eq!(
            rejected["rejected_count"].as_u64().unwrap(),
            observed["rejected_count"].as_u64().unwrap() + 1
        );
        assert!(rejected["ingress_age_ms"].is_u64());
        assert!(rejected["commit_age_ms"].is_u64());
    }

    #[test]
    fn diagnostic_ingress_delay_never_replaces_the_accepted_receipt_clock() {
        let _binding = crate::breath_composition_runtime::scoped_test_binding::install_polar();
        let token = acquisition_token_entered(Instant::now() - Duration::from_secs(2));
        assert!(commit_acc(token, || true));
        let (current, _) = status(false, false);
        assert!(
            current.fresh,
            "diagnostic entry cannot backdate acceptance freshness"
        );
        let observed = current.json();
        assert!(observed["ingress_age_ms"].as_u64().unwrap() >= 2000);
        assert!(observed["ingress_to_commit_ms"].as_u64().unwrap() >= 2000);
        assert!(observed["accepted_age_ms"].as_u64().unwrap() < 1000);
        let mut later = current;
        later.accepted_age_ms = Some(900);
        later.ingress_count += 1;
        assert!(
            current.same_readiness(later),
            "diagnostics must not churn readiness transitions"
        );
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
        assert_eq!(stale.json()["accepted_age_ms"], 1000);
        assert!(state
            .status(now - Duration::from_millis(1), false, false)
            .0
            .json()["accepted_age_ms"]
            .is_null());
        assert_eq!(overdue.overdue, Duration::from_nanos(1));
        assert!(
            !state
                .status(now - Duration::from_millis(1), false, false)
                .0
                .fresh
        );
    }
}
