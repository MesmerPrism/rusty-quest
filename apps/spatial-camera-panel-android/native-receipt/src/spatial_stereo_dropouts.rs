//! Fixed-memory diagnostic observations, separate from the qualification words.
//! Closed gaps count once on real progress. Repeated polling cannot count an
//! open stall twice. Only the native monotonic domain is used here.
use serde_json::{json, Value};

pub(crate) const BOUND_NS: u64 = 500_000_000;
const SAMPLES: usize = 4;
pub(crate) const COUNTER_COLUMNS: [&str; 33] = [
    "progress",
    "closed_gaps",
    "over_bound",
    "over_bound_gap_ns",
    "closed_plus_open_over_bound",
    "open_over_bound",
    "closed_gap_runs",
    "recovered_runs",
    "censored_runs",
    "max_run_intervals",
    "current_run_intervals",
    "max_closed_gap_ns",
    "demanded_ns",
    "demanded",
    "open_gap_ns",
    "waiting_first_progress",
    "waiting_first_ns",
    "first_waits_over_bound",
    "max_first_wait_ns",
    "censored_first_waits_over_bound",
    "epoch_changes",
    "epoch_censored_open_gaps",
    "demand_pauses",
    "censored_open_gaps",
    "rejected_observations",
    "clock_valid",
    "gap_bins",
    "source_age_observations",
    "source_age_over_bound",
    "max_adopted_source_age_ns",
    "source_age_bins",
    "current_source_age_ns",
    "source_age_open_over_bound",
];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Key {
    pub epoch: u64,
    pub sequence: u64,
}

#[derive(Clone, Copy)]
pub(crate) struct Progress {
    demanded: bool,
    observed_at: u64,
    demanded_ns: u64,
    demanded_since: u64,
    first_waits: u64,
    max_first_wait: u64,
    censored_first_waits: u64,
    last_at: u64,
    last_key: Option<Key>,
    progress: u64,
    gaps: u64,
    over_bound: u64,
    over_bound_ns: u64,
    max_gap: u64,
    bins: [u64; 4],
    runs: u64,
    recoveries: u64,
    censored_runs: u64,
    run_length: u64,
    max_run_length: u64,
    epochs: u64,
    epoch_censored: u64,
    pauses: u64,
    censored: u64,
    rejected: u64,
    samples: [[u64; 7]; SAMPLES],
    sample_count: usize,
    sample_next: usize,
    age_observations: u64,
    age_over_bound: u64,
    max_age: u64,
    age_bins: [u64; 4],
    last_age_key: Option<Key>,
    last_source_observed: Option<u64>,
}

impl Progress {
    pub(crate) const fn empty() -> Self {
        Self {
            demanded: false,
            observed_at: 0,
            demanded_ns: 0,
            demanded_since: 0,
            first_waits: 0,
            max_first_wait: 0,
            censored_first_waits: 0,
            last_at: 0,
            last_key: None,
            progress: 0,
            gaps: 0,
            over_bound: 0,
            over_bound_ns: 0,
            max_gap: 0,
            bins: [0; 4],
            runs: 0,
            recoveries: 0,
            censored_runs: 0,
            run_length: 0,
            max_run_length: 0,
            epochs: 0,
            epoch_censored: 0,
            pauses: 0,
            censored: 0,
            rejected: 0,
            samples: [[0; 7]; SAMPLES],
            sample_count: 0,
            sample_next: 2,
            age_observations: 0,
            age_over_bound: 0,
            max_age: 0,
            age_bins: [0; 4],
            last_age_key: None,
            last_source_observed: None,
        }
    }

    pub(crate) fn demand(&mut self, demanded: bool, now: u64) {
        if now < self.observed_at || now < self.last_at {
            self.rejected = self.rejected.saturating_add(1);
            return;
        }
        if self.demanded {
            self.demanded_ns = self.demanded_ns.saturating_add(now - self.observed_at);
        }
        if self.demanded && !demanded {
            self.pauses = self.pauses.saturating_add(1);
            if self.last_key.is_some() && now.saturating_sub(self.last_at) > BOUND_NS {
                self.censored = self.censored.saturating_add(1);
            }
            if self.last_key.is_none() && now - self.demanded_since > BOUND_NS {
                self.censored_first_waits = self.censored_first_waits.saturating_add(1);
            }
        }
        if self.demanded != demanded {
            if self.run_length != 0 {
                self.censored_runs = self.censored_runs.saturating_add(1);
                self.run_length = 0;
            }
            self.last_key = None;
            self.last_at = 0;
            self.last_age_key = None;
            self.last_source_observed = None;
            self.demanded_since = if demanded { now } else { 0 };
        }
        self.demanded = demanded;
        self.observed_at = now;
    }

    pub(crate) fn advance(&mut self, key: Key, now: u64) {
        if !self.demanded {
            return;
        }
        if now < self.observed_at || now < self.last_at || key.epoch == 0 || key.sequence == 0 {
            self.rejected = self.rejected.saturating_add(1);
            return;
        }
        if let Some(previous) = self.last_key {
            if previous.epoch == key.epoch && key.sequence <= previous.sequence {
                if key.sequence < previous.sequence {
                    self.rejected = self.rejected.saturating_add(1);
                }
                return;
            }
            if previous.epoch != key.epoch {
                self.epochs = self.epochs.saturating_add(1);
                if now - self.last_at > BOUND_NS {
                    self.epoch_censored = self.epoch_censored.saturating_add(1);
                }
                if self.run_length != 0 {
                    self.censored_runs = self.censored_runs.saturating_add(1);
                    self.run_length = 0;
                }
            } else {
                let gap = now - self.last_at;
                self.gaps = self.gaps.saturating_add(1);
                self.max_gap = self.max_gap.max(gap);
                let bin = if gap <= BOUND_NS {
                    0
                } else if gap <= 1_000_000_000 {
                    1
                } else if gap <= 2_000_000_000 {
                    2
                } else {
                    3
                };
                self.bins[bin] = self.bins[bin].saturating_add(1);
                if gap > BOUND_NS {
                    if self.run_length == 0 {
                        self.runs = self.runs.saturating_add(1);
                    }
                    self.run_length = self.run_length.saturating_add(1);
                    self.max_run_length = self.max_run_length.max(self.run_length);
                    self.over_bound = self.over_bound.saturating_add(1);
                    self.over_bound_ns = self.over_bound_ns.saturating_add(gap);
                    // Keep the first two incidents and the latest two. All
                    // incidents remain in counters even after sample rollover.
                    let index = if self.sample_count < SAMPLES {
                        self.sample_count
                    } else {
                        self.sample_next
                    };
                    self.samples[index] = [
                        self.last_at,
                        now,
                        gap,
                        previous.epoch,
                        previous.sequence,
                        key.epoch,
                        key.sequence,
                    ];
                    if self.sample_count < SAMPLES {
                        self.sample_count += 1;
                    } else {
                        self.sample_next = if index == 2 { 3 } else { 2 };
                    }
                } else if self.run_length != 0 {
                    self.recoveries = self.recoveries.saturating_add(1);
                    self.run_length = 0;
                }
            }
        }
        if self.last_key.is_none() {
            let wait = now - self.demanded_since;
            self.max_first_wait = self.max_first_wait.max(wait);
            if wait > BOUND_NS {
                self.first_waits = self.first_waits.saturating_add(1);
            }
        }
        self.last_at = now;
        self.last_key = Some(key);
        self.progress = self.progress.saturating_add(1);
    }

    pub(crate) fn snapshot(&self, now: u64) -> Value {
        let valid_clock = now >= self.observed_at && now >= self.last_at;
        let open = if self.demanded && self.last_key.is_some() && valid_clock {
            Some(now - self.last_at)
        } else {
            None
        };
        let open_over_bound = open.is_some_and(|gap| gap > BOUND_NS);
        let source_age = self
            .last_source_observed
            .filter(|_| self.demanded && valid_clock)
            .filter(|observed| *observed <= now)
            .map(|observed| now - observed);
        let demand_ns = self
            .demanded_ns
            .saturating_add(if self.demanded && valid_clock {
                now - self.observed_at
            } else {
                0
            });
        json!({"progress": self.progress, "closed_gaps": self.gaps,
            "over_bound": self.over_bound, "over_bound_gap_ns": self.over_bound_ns,
            "closed_plus_open_over_bound": self.over_bound.saturating_add(u64::from(open_over_bound)),
            "open_over_bound": open_over_bound,
            "closed_gap_runs": self.runs, "recovered_runs": self.recoveries,
            "censored_runs": self.censored_runs, "max_run_intervals": self.max_run_length,
            "current_run_intervals": self.run_length,
            "max_closed_gap_ns": self.max_gap, "gap_bins": self.bins,
            "demanded_ns": demand_ns, "demanded": self.demanded,
            "open_gap_ns": open, "waiting_first_progress": self.demanded && self.last_key.is_none(),
            "waiting_first_ns": if self.demanded && self.last_key.is_none() && valid_clock { Some(now-self.demanded_since) } else { None },
            "first_waits_over_bound": self.first_waits, "max_first_wait_ns": self.max_first_wait,
            "censored_first_waits_over_bound": self.censored_first_waits,
            "epoch_changes": self.epochs, "epoch_censored_open_gaps": self.epoch_censored, "demand_pauses": self.pauses,
            "censored_open_gaps": self.censored, "rejected_observations": self.rejected,
            "samples": &self.samples[..self.sample_count], "clock_valid": valid_clock,
            "source_age_observations":self.age_observations,"source_age_over_bound":self.age_over_bound,
            "max_adopted_source_age_ns":self.max_age,"source_age_bins":self.age_bins,
            "current_source_age_ns":source_age,"source_age_open_over_bound":source_age.is_some_and(|age|age>BOUND_NS)})
    }
    /// Same native local observation age as qualification words59/60, counted
    /// only once per distinct final adoption. A held old source has an open
    /// age, rather than an extra late-frame count on every GPU poll.
    pub(crate) fn source_age_at_progress(&mut self, key: Key, now: u64, observed: u64) {
        if !self.demanded
            || self.last_key != Some(key)
            || self.last_at != now
            || observed > now
            || self.last_age_key == Some(key)
        {
            return;
        }
        let age = now - observed;
        self.age_observations = self.age_observations.saturating_add(1);
        self.max_age = self.max_age.max(age);
        let bin = if age <= BOUND_NS {
            0
        } else if age <= 1_000_000_000 {
            1
        } else if age <= 2_000_000_000 {
            2
        } else {
            3
        };
        self.age_bins[bin] = self.age_bins[bin].saturating_add(1);
        if age > BOUND_NS {
            self.age_over_bound = self.age_over_bound.saturating_add(1);
        }
        self.last_age_key = Some(key);
        self.last_source_observed = Some(observed);
    }
    pub(crate) fn compact_snapshot(&self, now: u64) -> Value {
        let full = self.snapshot(now);
        json!({"values":COUNTER_COLUMNS.iter().map(|key|full[*key].clone()).collect::<Vec<_>>(),
            "samples":full["samples"]})
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn key(sequence: u64) -> Key {
        Key { epoch: 7, sequence }
    }
    #[test]
    fn recurrent_dropouts_survive_first_fault_and_samples_rollover() {
        let mut s = Progress::empty();
        s.demand(true, 1);
        s.advance(key(1), 10);
        for n in 2..=21 {
            s.advance(key(n), 10 + (n - 1) * 600_000_000);
        }
        assert_eq!((s.progress, s.gaps, s.over_bound), (21, 20, 20));
        assert_eq!(s.bins, [0, 20, 0, 0]);
        assert_eq!((s.runs, s.run_length, s.recoveries), (1, 20, 0));
        s.advance(key(22), 10 + 20 * 600_000_000 + 20_000_000);
        assert_eq!((s.runs, s.run_length, s.recoveries), (1, 0, 1));
        assert_eq!(s.sample_count, 4);
        assert_eq!(s.samples[0][4], 1);
        assert_eq!(s.samples[1][4], 2);
        assert!(s.samples[2..].iter().any(|v| v[6] == 21));
    }
    #[test]
    fn held_identity_and_repeated_open_snapshots_do_not_double_count() {
        let mut s = Progress::empty();
        s.demand(true, 1);
        s.advance(key(1), 10);
        s.advance(key(1), 900_000_010);
        for _ in 0..10 {
            assert_eq!(s.snapshot(900_000_010)["open_gap_ns"], 900_000_000u64);
        }
        assert_eq!(s.snapshot(900_000_010)["closed_plus_open_over_bound"], 1);
        assert_eq!(s.over_bound, 0);
        s.advance(key(2), 900_000_010);
        assert_eq!(s.over_bound, 1);
        assert_eq!(s.snapshot(900_000_010)["open_gap_ns"], 0);
        assert_eq!(s.snapshot(900_000_010)["closed_plus_open_over_bound"], 1);
    }
    #[test]
    fn intentional_pause_and_new_epoch_do_not_create_gap_failures() {
        let mut s = Progress::empty();
        s.demand(true, 1);
        s.advance(key(1), 10);
        s.demand(false, 700_000_010);
        s.demand(true, 8_000_000_010);
        s.advance(key(2), 8_000_000_011);
        s.advance(
            Key {
                epoch: 8,
                sequence: 1,
            },
            9_000_000_011,
        );
        assert_eq!((s.over_bound, s.epochs, s.pauses, s.censored), (0, 1, 1, 1));
        assert_eq!(s.epoch_censored, 1);
        assert_eq!(s.snapshot(9_000_000_011)["demanded_ns"], 1_700_000_010u64);
    }
    #[test]
    fn threshold_bins_regression_and_unavailable_progress_are_explicit() {
        let mut s = Progress::empty();
        s.demand(true, 1);
        assert_eq!(s.snapshot(800_000_000)["waiting_first_progress"], true);
        assert_eq!(s.snapshot(800_000_000)["waiting_first_ns"], 799_999_999u64);
        s.advance(key(1), 10);
        s.advance(key(2), 500_000_010);
        s.advance(key(3), 1_500_000_010);
        s.advance(key(4), 3_500_000_010);
        s.advance(key(5), 5_500_000_011);
        s.advance(key(4), 5_500_000_012);
        s.advance(key(6), 9);
        assert_eq!(s.bins, [1, 1, 1, 1]);
        assert_eq!(s.over_bound, 3);
        assert_eq!(s.rejected, 2);
        assert_eq!(s.last_key, Some(key(5)));
        assert_eq!(s.snapshot(9)["clock_valid"], false);
    }
    #[test]
    fn worst_unsigned_summary_remains_bounded() {
        let mut s = Progress::empty();
        s.demand(true, 1);
        s.advance(key(1), 2);
        for n in 2..=9 {
            s.advance(key(n), n * (u64::MAX / 10));
        }
        s.progress = u64::MAX;
        s.gaps = u64::MAX;
        s.over_bound = u64::MAX;
        s.over_bound_ns = u64::MAX;
        s.max_gap = u64::MAX;
        s.bins = [u64::MAX; 4];
        s.demanded_ns = u64::MAX;
        s.epochs = u64::MAX;
        s.epoch_censored = u64::MAX;
        s.pauses = u64::MAX;
        s.censored = u64::MAX;
        s.rejected = u64::MAX;
        s.first_waits = u64::MAX;
        s.runs = u64::MAX;
        s.recoveries = u64::MAX;
        s.censored_runs = u64::MAX;
        s.run_length = u64::MAX;
        s.max_run_length = u64::MAX;
        s.max_first_wait = u64::MAX;
        s.censored_first_waits = u64::MAX;
        s.samples = [[u64::MAX; 7]; SAMPLES];
        s.age_observations = u64::MAX;
        s.age_over_bound = u64::MAX;
        s.max_age = u64::MAX;
        s.age_bins = [u64::MAX; 4];
        s.last_source_observed = Some(0);
        assert!(s.snapshot(u64::MAX).to_string().len() < 2560);
        assert!(s.compact_snapshot(u64::MAX).to_string().len() < 1600);
    }
    #[test]
    fn regularly_advancing_late_sources_count_age_failures_without_gap_failures() {
        let mut s = Progress::empty();
        s.demand(true, 1_000_000_000);
        for n in 1..=10 {
            let now = 1_000_000_000 + n * 20_000_000;
            s.advance(key(n), now);
            s.source_age_at_progress(key(n), now, now - 750_000_000);
            s.source_age_at_progress(key(n), now, now - 750_000_000);
        }
        assert_eq!(s.over_bound, 0);
        assert_eq!(s.age_observations, 10);
        assert_eq!(s.age_over_bound, 10);
        assert_eq!(s.age_bins, [0, 10, 0, 0]);
        for _ in 0..5 {
            assert_eq!(
                s.snapshot(1_400_000_000)["current_source_age_ns"],
                950_000_000u64
            );
        }
        assert_eq!(s.age_over_bound, 10);
        s.demand(false, 1_400_000_000);
        assert!(s.snapshot(2_000_000_000)["current_source_age_ns"].is_null());
    }
}
