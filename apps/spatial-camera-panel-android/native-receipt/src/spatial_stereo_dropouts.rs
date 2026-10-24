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

// v2 is deliberately independent of the ARM-cumulative v1 diagnostics above.
// Every count is complete, fixed-memory and signed-64-safe for Android JSON.
const MAX_COUNTER: u64 = i64::MAX as u64;
const SERIES_NAMES: [&str; 4] = ["gpu_retirement", "own_distinct_adoption", "peer_distinct_adoption", "both_origin_retirement"];
fn increment(value: &mut u64, overflowed: &mut bool) {
    if *value >= MAX_COUNTER { *overflowed = true; } else { *value += 1; }
}
#[derive(Clone, Copy)]
struct FiniteSeries {
    required_since_ns: u64,
    continuity_generation: u64,
    epoch_key: [u64; 5],
    first_progress_ns: Option<u64>,
    last_progress_ns: Option<u64>,
    last_key: [u64; 4],
    progress_count: u64,
    closed_gap_count: u64,
    gap_over_bound_count: u64,
    first_wait_over_bound_count: u64,
    source_age_observation_count: u64,
    source_age_over_bound_count: u64,
    max_closed_gap_ns: u64,
    max_adopted_source_age_ns: u64,
    source_epoch_change_count: u64,
    demand_transition_count: u64,
    censored_interval_count: u64,
    rejected_observation_count: u64,
    last_source_observed_ns: Option<u64>,
    overflowed: bool,
}
impl FiniteSeries {
    const fn empty() -> Self { Self {
        required_since_ns:0, continuity_generation:1, epoch_key:[0;5], first_progress_ns:None,
        last_progress_ns:None,last_key:[0;4],progress_count:0,closed_gap_count:0,gap_over_bound_count:0,
        first_wait_over_bound_count:0,source_age_observation_count:0,source_age_over_bound_count:0,
        max_closed_gap_ns:0,max_adopted_source_age_ns:0,source_epoch_change_count:0,demand_transition_count:0,
        censored_interval_count:0,rejected_observation_count:0,last_source_observed_ns:None,overflowed:false,
    } }
    fn reject(&mut self) {increment(&mut self.rejected_observation_count,&mut self.overflowed);}
    fn advance(&mut self, key:[u64;4], now:u64, source_observed:Option<u64>, origin:usize) -> bool {
        let previous=self.last_key;
        let valid_key = key[0]>0 && key[1]>0 && match origin {1=>key[2]>0,2=>key[3]>0,3=>key[2]>0&&key[3]>0,_=>true};
        let regressed=self.last_progress_ns.is_some() && (key[0]!=previous[0] || key[1]<=previous[1]
            || (origin==1 && key[2]<=previous[2]) || (origin==2 && key[3]<=previous[3]));
        if !valid_key || regressed || now<self.required_since_ns || self.last_progress_ns.is_some_and(|v|now<v)
            || (origin!=0 && source_observed.is_none_or(|v|v>now)) {
            self.reject();return false;
        }
        if let Some(previous)=self.last_progress_ns {
            let gap=now-previous;increment(&mut self.closed_gap_count,&mut self.overflowed);
            self.max_closed_gap_ns=self.max_closed_gap_ns.max(gap);
            if gap>BOUND_NS {increment(&mut self.gap_over_bound_count,&mut self.overflowed);}
        } else {
            self.first_progress_ns=Some(now);
            if now-self.required_since_ns>BOUND_NS {increment(&mut self.first_wait_over_bound_count,&mut self.overflowed);}
        }
        if origin!=0 {if let Some(observed)=source_observed {
            let age=now-observed;increment(&mut self.source_age_observation_count,&mut self.overflowed);
            self.max_adopted_source_age_ns=self.max_adopted_source_age_ns.max(age);
            if age>BOUND_NS {increment(&mut self.source_age_over_bound_count,&mut self.overflowed);}
            self.last_source_observed_ns=Some(observed);
        }}
        self.last_progress_ns=Some(now);self.last_key=key;increment(&mut self.progress_count,&mut self.overflowed);true
    }
    fn snapshot(&self, origin:usize, now:u64) -> Value {
        json!({"name":SERIES_NAMES[origin],"required":true,"required_since_ns":self.required_since_ns,
            "continuity_generation":self.continuity_generation,"epoch_key":self.epoch_key,
            "first_progress_ns":self.first_progress_ns,"last_progress_ns":self.last_progress_ns,"last_key":self.last_key,
            "progress_count":self.progress_count,"closed_gap_count":self.closed_gap_count,"gap_over_bound_count":self.gap_over_bound_count,
            "first_wait_over_bound_count":self.first_wait_over_bound_count,"source_age_applicable":origin!=0,
            "source_age_observation_count":self.source_age_observation_count,"source_age_over_bound_count":self.source_age_over_bound_count,
            "max_closed_gap_ns":self.max_closed_gap_ns,"max_adopted_source_age_ns":self.max_adopted_source_age_ns,
            "source_epoch_change_count":self.source_epoch_change_count,"demand_transition_count":self.demand_transition_count,
            "censored_interval_count":self.censored_interval_count,"rejected_observation_count":self.rejected_observation_count,
            "waiting_first_progress":self.last_progress_ns.is_none(),
            "open_gap_ns":now.checked_sub(self.last_progress_ns.unwrap_or(self.required_since_ns)),
            "current_source_age_ns":self.last_source_observed_ns.and_then(|v|now.checked_sub(v)),"overflowed":self.overflowed})
    }
}
fn bounded_id(value:Option<&str>)->Option<String> {
    value.filter(|v|!v.is_empty()&&v.len()<=256&&v.is_ascii()&&!v.bytes().any(|b|b<32)).map(str::to_owned)
}
#[derive(Clone, PartialEq, Eq)]
struct StartIdentity {
    process:u64, arm:u64, sequence:u64,
    original:Option<String>, action:Option<String>, activation:Option<String>,
}
impl StartIdentity {
    fn new(process:u64,arm:u64,sequence:u64,original:Option<&str>,action:Option<&str>,activation:Option<&str>)->Self {
        Self {process,arm,sequence,original:bounded_id(original),action:bounded_id(action),activation:bounded_id(activation)}
    }
    fn valid(&self)->bool {
        [self.process,self.arm,self.sequence].iter().all(|v|*v>0&&*v<=MAX_COUNTER)
            &&self.original.is_some()&&self.action.is_some()&&self.activation.is_some()
    }
}
struct AdmittedStart {
    identity:StartIdentity, token:u64, sealed:bool, stopped:bool,
    state_valid:bool, clock_valid:bool, rejected:u64, censored:u64,
}
pub(crate) struct Freshness {
    pub(crate) sequence:u64,
    admitted:Option<AdmittedStart>, next_token:u64,
    start_original_id:Option<String>,start_platform_action_id:Option<String>,activation_id:Option<String>,
    started_ns:Option<u64>, pub(crate) phase:&'static str, stop_requested_ns:Option<u64>,
    series:[FiniteSeries;4], context:Option<([u64;5],u64,[bool;2])>,last_clock:u64,
    pub(crate) sample_sequence:u64,pub(crate) state_valid:bool,pub(crate) clock_valid:bool, counter_overflowed:bool,
}
impl Freshness {
    pub(crate) const fn empty()->Self {Self {sequence:0,admitted:None,next_token:0,start_original_id:None,start_platform_action_id:None,activation_id:None,
        started_ns:None,phase:"starting",stop_requested_ns:None,series:[FiniteSeries::empty();4],context:None,last_clock:0,
        sample_sequence:0,state_valid:false,clock_valid:true,counter_overflowed:false}}
    pub(crate) fn observe_clock(&mut self,now:Option<u64>)->Option<u64> {
        match now {
            Some(now) if now>=self.last_clock&&now<=MAX_COUNTER=>{self.last_clock=now;Some(now)},
            _=>{self.clock_valid=false;if let Some(candidate)=self.admitted.as_mut(){candidate.clock_valid=false;}None}
        }
    }
    fn revision(&mut self){increment(&mut self.sample_sequence,&mut self.counter_overflowed);}
    pub(crate) fn admitted_start(&mut self,process:u64,arm:u64,sequence:u64,original:Option<&str>,action:Option<&str>,activation:Option<&str>,now:Option<u64>) {
        let identity=StartIdentity::new(process,arm,sequence,original,action,activation);
        if let Some(previous)=self.admitted.as_ref() {
            if identity==previous.identity {return;} // Reentry never repairs faults or reopens Stop.
            if !previous.stopped || sequence<=previous.identity.sequence
                || identity.original==previous.identity.original || identity.action==previous.identity.action
                || identity.activation==previous.identity.activation {
                self.reject(true);return;
            }
        }
        increment(&mut self.next_token,&mut self.counter_overflowed);
        let state_valid=identity.valid();
        self.admitted=Some(AdmittedStart {identity,token:self.next_token,sealed:false,stopped:false,
            state_valid,clock_valid:true,rejected:0,censored:0});
        self.observe_clock(now);self.revision();
    }
    pub(crate) fn completed_start(&mut self,process:u64,arm:u64,sequence:u64,original:Option<&str>,action:Option<&str>,activation:Option<&str>,now:Option<u64>)->bool {
        let identity=StartIdentity::new(process,arm,sequence,original,action,activation);
        if self.admitted.is_none() {
            // A missed admission is unavailable evidence for this exact Start,
            // not permission to repair it by later replaying admission.
            increment(&mut self.next_token,&mut self.counter_overflowed);
            self.admitted=Some(AdmittedStart {identity,token:self.next_token,sealed:true,stopped:false,
                state_valid:false,clock_valid:true,rejected:1,censored:1});
            self.observe_clock(now);self.state_valid=false;self.phase="failed";self.revision();return false;
        }
        let candidate=self.admitted.as_ref().expect("present admitted Start");
        if identity!=candidate.identity {self.observe_clock(now);self.reject(true);return false;}
        if candidate.sealed||candidate.stopped {return false;}
        let at=self.observe_clock(now);
        if !identity.valid(){
            let candidate=self.admitted.as_mut().expect("matched invalid Start");
            candidate.sealed=true;candidate.state_valid=false;self.state_valid=false;self.phase="failed";self.revision();return false;
        }
        let candidate=self.admitted.as_mut().expect("matched admitted Start");
        candidate.sealed=true; // Even an unavailable completion clock cannot be retried into clean evidence.
        let Some(now)=at else {candidate.state_valid=false;self.state_valid=false;self.phase="failed";return false;};
        let (valid,clock,rejected,censored)=(candidate.state_valid,candidate.clock_valid,candidate.rejected,candidate.censored);
        self.sequence=sequence;self.start_original_id=identity.original;self.start_platform_action_id=identity.action;self.activation_id=identity.activation;
        self.started_ns=Some(now);self.phase="active";self.stop_requested_ns=None;self.series=[FiniteSeries::empty();4];
        for series in &mut self.series {
            series.required_since_ns=now;series.rejected_observation_count=rejected;series.censored_interval_count=censored;
        }
        self.context=None;self.state_valid=valid&&clock;self.clock_valid=clock;self.revision();true
    }
    pub(crate) fn active_stamp(&self)->Option<u64>{
        self.admitted.as_ref().filter(|c|c.sealed&&!c.stopped&&self.phase=="active"&&self.started_ns.is_some()).map(|c|c.token)
    }
    pub(crate) fn record_stamp(&self,process:u64,arm:u64)->Option<u64>{
        self.admitted.as_ref().filter(|c|!c.stopped&&c.identity.process==process&&c.identity.arm==arm).map(|c|c.token)
    }
    pub(crate) fn reject(&mut self,censored:bool){
        if let Some(candidate)=self.admitted.as_mut() {
            candidate.state_valid=false;
            if !candidate.sealed {
                increment(&mut candidate.rejected,&mut self.counter_overflowed);
                if censored {increment(&mut candidate.censored,&mut self.counter_overflowed);}
                self.revision();return;
            }
        }
        if self.started_ns.is_none(){return;}
        self.state_valid=false;self.revision();
        for row in &mut self.series {row.reject();if censored{increment(&mut row.censored_interval_count,&mut row.overflowed);}}
    }
    pub(crate) fn stop(&mut self,now:Option<u64>){
        let Some(candidate)=self.admitted.as_mut() else {return;};
        if candidate.stopped {return;}
        candidate.stopped=true;
        self.stop_requested_ns=self.observe_clock(now);self.phase="stopping";self.revision();
    }
    pub(crate) fn stopped(&mut self){if self.phase=="stopping"{self.phase="stopped";self.revision();}}
    pub(crate) fn record_context(&mut self,epoch:[u64;5],config:u64,demand:[bool;2],now:Option<u64>){
        if self.admitted.is_none(){return;}
        if self.observe_clock(now).is_none(){self.reject(true);return;}
        if epoch.iter().any(|v|*v>MAX_COUNTER)||config>MAX_COUNTER{self.reject(true);return;}
        if self.active_stamp().is_none(){return;}
        // An absent image does not erase its known epoch. A first real source
        // binds that unknown coordinate once; its original first-wait remains.
        let mut epoch=epoch;
        if let Some((previous,_,_))=self.context {
            for index in 1..5 {if epoch[index]==0 {epoch[index]=previous[index];}}
        }
        let (changed,demand_changed)=self.context.map_or((false,!demand.iter().all(|v|*v)),|(e,c,d)|
            ((0..5).any(|i|e[i]!=0&&e[i]!=epoch[i])||c!=config,d!=demand));
        if changed||demand_changed {
            self.state_valid=false;
            for row in &mut self.series {
                increment(&mut row.continuity_generation,&mut row.overflowed);
                increment(&mut row.censored_interval_count,&mut row.overflowed);
                if changed{increment(&mut row.source_epoch_change_count,&mut row.overflowed);}
                if demand_changed{increment(&mut row.demand_transition_count,&mut row.overflowed);}
            }
        }
        self.context=Some((epoch,config,demand));
        for (i,row) in self.series.iter_mut().enumerate(){row.epoch_key=match i {0=>[epoch[0],0,0,0,0],1=>[epoch[0],epoch[1],epoch[2],0,0],2=>[epoch[0],0,0,epoch[3],epoch[4]],_=>epoch};}
        self.revision();
    }
    pub(crate) fn retired(&mut self,stamp:Option<u64>,epoch:[u64;5],key:[u64;4],events:[bool;4],observed:[Option<u64>;2],now:Option<u64>){
        if self.active_stamp().is_none(){return;}
        if stamp!=self.active_stamp(){self.reject(true);return;}
        let Some(now)=self.observe_clock(now) else {self.reject(true);return;};
        if key.iter().chain(epoch.iter()).any(|v|*v>MAX_COUNTER){self.reject(true);return;}
        for i in 0..4 {if events[i] {
            let source=match i {1=>observed[0],2=>observed[1],3=>observed[0].zip(observed[1]).map(|(a,b)|a.min(b)),_=>None};
            let row_key=match i {0=>[key[0],key[1],0,0],1=>[key[0],key[1],key[2],0],2=>[key[0],key[1],0,key[3]],_=>key};
            if !self.series[i].advance(row_key,now,source,i){self.state_valid=false;}
        }}
        self.revision();
    }
    pub(crate) fn overflowed(&self)->bool{self.counter_overflowed||self.series.iter().any(|s|s.overflowed)}
    pub(crate) fn lifetime(&self)->Value{json!({"sequence":self.sequence,"start_original_id":self.start_original_id,
        "start_platform_action_id":self.start_platform_action_id,"activation_id":self.activation_id,"started_ns":self.started_ns,
        "phase":self.phase,"stop_requested_ns":self.stop_requested_ns})}
    pub(crate) fn snapshot(&self,now:u64)->Value{Value::Array(self.series.iter().enumerate().map(|(i,s)|s.snapshot(i,now)).collect())}
}

#[cfg(test)]
pub(crate) mod finite_tests {
    use super::*;
    trait TestStart {
        fn complete_for_test(&mut self,sequence:u64,original:Option<&str>,action:Option<&str>,activation:Option<&str>,now:Option<u64>);
    }
    impl TestStart for Freshness {
        fn complete_for_test(&mut self,sequence:u64,original:Option<&str>,action:Option<&str>,activation:Option<&str>,now:Option<u64>){
            self.admitted_start(7,1,sequence,original,action,activation,now);
            self.completed_start(7,1,sequence,original,action,activation,now);
        }
    }
    fn life() -> Freshness {
        let mut f = Freshness::empty();
        f.complete_for_test(1, Some("original.1"), Some("action.1"), Some("activation.1"), Some(10));
        f
    }
    fn event(f: &mut Freshness, ordinal: u64, now: u64, observed: u64) {
        f.record_context([8,7,1,7,2], 3, [true,true], Some(now));
        f.retired(Some(1), [8,7,1,7,2], [8,ordinal,ordinal,ordinal], [true,true,true,true], [Some(observed),Some(observed)], Some(now));
    }
    pub(crate) fn maximum_fixture() -> (Freshness,[Progress;3]) {
        let mut f=life();let max=i64::MAX as u64;
        f.start_original_id=Some("\"".repeat(256));f.start_platform_action_id=Some("\\".repeat(256));f.activation_id=Some("x".repeat(256));
        f.sequence=max;f.started_ns=Some(max);f.stop_requested_ns=Some(max);f.sample_sequence=max;
        for row in &mut f.series {
            row.required_since_ns=max;row.continuity_generation=max;row.epoch_key=[max;5];row.first_progress_ns=Some(max);
            row.last_progress_ns=Some(max);row.last_key=[max;4];row.progress_count=max;row.closed_gap_count=max;
            row.gap_over_bound_count=max;row.first_wait_over_bound_count=max;row.source_age_observation_count=max;
            row.source_age_over_bound_count=max;row.max_closed_gap_ns=max;row.max_adopted_source_age_ns=max;
            row.source_epoch_change_count=max;row.demand_transition_count=max;row.censored_interval_count=max;
            row.rejected_observation_count=max;row.last_source_observed_ns=Some(0);row.overflowed=true;
        }
        let mut old=Progress::empty();old.demand(true,1);old.advance(Key {epoch:7,sequence:1},2);
        old.progress = max;
        old.gaps = max;
        old.over_bound = max;
        old.over_bound_ns = max;
        old.max_gap = max;
        old.bins = [max; 4];
        old.demanded_ns = max;
        old.epochs = max;
        old.epoch_censored = max;
        old.pauses = max;
        old.censored = max;
        old.rejected = max;
        old.first_waits = max;
        old.runs = max;
        old.recoveries = max;
        old.censored_runs = max;
        old.run_length = max;
        old.max_run_length = max;
        old.max_first_wait = max;
        old.censored_first_waits = max;
        old.samples = [[max; 7]; SAMPLES];
        old.age_observations = max;
        old.age_over_bound = max;
        old.max_age = max;
        old.age_bins = [max; 4];
        old.last_source_observed = Some(0);
        old.sample_count=SAMPLES;old.demanded=true;old.observed_at=max;old.last_at=1;old.over_bound=max-1;
        (f,[old;3])
    }
    #[test]
    fn finite_complete_counts_retain_smaller_and_separated_incidents() {
        let mut f=life(); event(&mut f,1,20,20);
        for (n,t) in [1_000_000_020,1_010_000_020,1_610_000_020,1_620_000_020,2_220_000_020,2_820_000_020,3_420_000_020,4_020_000_020].into_iter().enumerate(){event(&mut f,n as u64+2,t,t);}
        let rows=f.snapshot(4_020_000_020);
        for row in rows.as_array().unwrap(){assert_eq!(row["gap_over_bound_count"],6);assert_eq!(row["max_closed_gap_ns"],1_000_000_000u64);assert_eq!(row["closed_gap_count"],8);}
    }
    #[test]
    fn startup_and_boundaries_and_held_sources_remain_visible() {
        let mut f=life(); event(&mut f,1,750_000_010,10);
        let rows=f.snapshot(1_350_000_011);
        assert_eq!(rows[0]["first_wait_over_bound_count"],1);
        assert_eq!(rows[0]["open_gap_ns"],600_000_001u64);
        assert_eq!(rows[1]["current_source_age_ns"],1_350_000_001u64);
        assert_eq!(rows[3]["source_age_over_bound_count"],1);
        assert!(rows[0]["current_source_age_ns"].is_null());
        let mut exact=life(); event(&mut exact,1,500_000_010,10);event(&mut exact,2,1_000_000_010,500_000_010);
        assert_eq!(exact.snapshot(1_000_000_010)[3]["gap_over_bound_count"],0);
        assert_eq!(exact.snapshot(1_000_000_010)[3]["first_wait_over_bound_count"],0);
    }
    #[test]
    fn missing_both_and_fast_gpu_with_late_sources_are_not_progress() {
        let mut f=life();
        f.record_context([8,7,1,7,2],3,[true,true],Some(20));
        f.retired(Some(1),[8,7,1,7,2],[8,1,1,0],[true,true,false,false],[Some(10),None],Some(20));
        assert_eq!(f.snapshot(800_000_010)[3]["waiting_first_progress"],true);
        assert_eq!(f.snapshot(800_000_010)[3]["open_gap_ns"],800_000_000u64);
        for n in 2..12 {event(&mut f,n,1_000_000_000+n*20_000_000,100);}
        assert_eq!(f.snapshot(1_240_000_000)[1]["source_age_over_bound_count"],10);
    }
    #[test]
    fn initial_unknown_source_binds_without_erasing_startup_wait_or_known_epochs() {
        let mut f=life();f.record_context([8,7,1,0,0],3,[true,true],Some(20));
        f.retired(Some(1),[8,7,1,0,0],[8,1,1,0],[true,true,false,false],[Some(20),None],Some(20));
        f.record_context([8,7,1,7,2],3,[true,true],Some(30));
        f.retired(Some(1),[8,7,1,7,2],[8,2,2,1],[true,true,true,true],[Some(30);2],Some(30));
        assert!(f.state_valid);assert_eq!(f.snapshot(30)[3]["first_progress_ns"],30);
        assert_eq!(f.snapshot(30)[3]["required_since_ns"],10);
        f.record_context([8,7,1,0,0],3,[true,true],Some(40));
        assert!(f.state_valid);assert_eq!(f.snapshot(40)[3]["epoch_key"],json!([8,7,1,7,2]));
        f.record_context([8,7,1,7,3],3,[true,true],Some(50));
        f.record_context([8,7,1,7,2],3,[true,true],Some(60));
        assert!(!f.state_valid);assert_eq!(f.snapshot(60)[3]["source_epoch_change_count"],2);
    }
    #[test]
    fn transitions_rejections_clock_and_overflow_are_sticky() {
        let mut f=life();event(&mut f,1,20,20);
        f.record_context([9,7,1,7,2],4,[false,true],Some(30));
        f.record_context([8,7,1,7,2],3,[true,true],Some(40));
        event(&mut f,2,50,50);
        let rows=f.snapshot(60);
        assert_eq!(rows[0]["source_epoch_change_count"],2);
        assert_eq!(rows[0]["demand_transition_count"],2);
        assert_eq!(rows[0]["censored_interval_count"],2);
        assert!(!f.state_valid);
        f.observe_clock(Some(1));f.observe_clock(Some(100));assert!(!f.clock_valid);
        let mut overflow=life();overflow.series[0].progress_count=i64::MAX as u64;
        event(&mut overflow,1,20,20);assert!(overflow.overflowed());assert_eq!(overflow.series[0].progress_count,i64::MAX as u64);
    }
    #[test]
    fn a_higher_sequence_cannot_relabel_the_same_actual_start() {
        let mut f=life();f.stop(Some(20));
        f.complete_for_test(2,Some("original.1"),Some("action.1"),Some("activation.1"),Some(30));
        assert_eq!(f.sequence,1);assert!(!f.state_valid);assert_eq!(f.phase,"stopping");
        f.complete_for_test(2,Some("original.2"),Some("action.2"),Some("activation.2"),Some(40));
        assert_eq!(f.sequence,2);assert!(f.state_valid);
    }
    #[test]
    fn old_pending_and_duplicate_start_cannot_repair_lifetime() {
        let mut f=life();
        f.retired(None,[8,7,1,7,2],[8,1,1,1],[true;4],[Some(20);2],Some(20));
        event(&mut f,2,30,30);assert!(!f.state_valid);
        assert_eq!(f.snapshot(30)[0]["rejected_observation_count"],1);
        f.complete_for_test(1,Some("original.1"),Some("action.1"),Some("activation.1"),Some(40));assert!(!f.state_valid);
        f.stop(Some(50));f.complete_for_test(1,Some("original.1"),Some("action.1"),Some("activation.1"),Some(60));assert_eq!(f.phase,"stopping");
        f.complete_for_test(2,Some("original.2"),Some("action.2"),Some("activation.2"),Some(70));
        assert_eq!(f.sequence,2);assert!(f.state_valid);assert_eq!(f.snapshot(70)[0]["progress_count"],0);
        f.complete_for_test(3,None,None,None,Some(80));assert_eq!(f.sequence,2);assert!(!f.state_valid);
    }
}
