//! Bounded, read-only operator observation. Never used as a breathing driver.

use std::collections::VecDeque;

pub(crate) const HISTORY_MS: u64 = 60_000;
pub(crate) const PREVIEW_MS: u64 = 10_000;
const SAMPLE_INTERVAL_MS: u64 = 100;

#[derive(Clone, Debug)]
pub(crate) struct Sample {
    pub id: u64,
    pub active_ms: u64,
    pub observed_ns: u64,
    pub source: &'static str,
    pub input_generation: u64,
    pub phase: &'static str,
    pub tracking: &'static str,
    pub volume: Option<f32>,
    pub quality: f32,
}

#[derive(Default)]
pub(crate) struct History {
    run: String,
    sequence: u64,
    samples: VecDeque<Sample>,
    pending_gap: bool,
    input_source: Option<(&'static str, u64)>,
}

impl History {
    pub(crate) fn observe(&mut self, run: &str, mut sample: Sample) {
        if self.run != run {
            self.run = run.to_owned();
            self.samples.clear();
            self.sequence = 0;
            self.pending_gap = false;
            self.input_source = None;
        }
        if self
            .samples
            .back()
            .is_some_and(|last| sample.active_ms < last.active_ms)
        {
            return;
        }
        self.pending_gap |= sample.volume.is_none()
            || sample.tracking != "valid"
            || self
                .input_source
                .is_some_and(|source| source != (sample.source, sample.input_generation));
        self.input_source = Some((sample.source, sample.input_generation));
        if self.samples.back().is_some_and(|last| {
            sample.active_ms < last.active_ms.saturating_add(SAMPLE_INTERVAL_MS)
        }) {
            return;
        }
        // A short invalid interval must survive decimation, including when a
        // client already read the previous bucket. Never mutate a published ID.
        if self.pending_gap && sample.volume.is_some() && sample.tracking == "valid" {
            sample.volume = None;
            sample.tracking = "gap";
            sample.phase = "unknown";
        }
        self.pending_gap = false;
        self.sequence += 1;
        sample.id = self.sequence;
        self.samples.push_back(sample);
        let cutoff = self
            .samples
            .back()
            .unwrap()
            .active_ms
            .saturating_sub(HISTORY_MS);
        while self
            .samples
            .front()
            .is_some_and(|first| first.active_ms < cutoff)
            || self.samples.len() > 601
        {
            self.samples.pop_front();
        }
    }

    pub(crate) fn read(&self, run: &str, after: u64) -> Vec<Sample> {
        if self.run != run {
            return Vec::new();
        }
        self.samples
            .iter()
            .filter(|s| s.id > after)
            .cloned()
            .collect()
    }

    pub(crate) fn latest(&self, run: &str) -> Option<&Sample> {
        (self.run == run).then(|| self.samples.back()).flatten()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(t: u64, valid: bool) -> Sample {
        Sample {
            id: 0,
            active_ms: t,
            observed_ns: t * 1_000_000,
            source: "polar-acc",
            input_generation: 1,
            phase: "inhale",
            tracking: if valid { "valid" } else { "stale" },
            volume: if valid { Some(0.4) } else { None },
            quality: 0.9,
        }
    }

    #[test]
    fn bounded_history_downsamples_and_resets_across_sessions() {
        let mut ring = History::default();
        for t in 0..70_000 {
            ring.observe("1:2", sample(t, true));
        }
        let all = ring.read("1:2", 0);
        assert!(all.len() <= 601);
        assert!(all.first().unwrap().active_ms >= 9_900);
        assert!(all
            .windows(2)
            .all(|w| w[1].active_ms - w[0].active_ms >= 100));
        assert!(ring.read("1:3", 0).is_empty());
        ring.observe("1:3", sample(0, true));
        assert_eq!(ring.read("1:3", 0).len(), 1);
        assert_eq!(ring.read("1:3", 0)[0].id, 1);
    }

    #[test]
    fn cursor_and_invalid_samples_preserve_gaps_without_invented_volume() {
        let mut ring = History::default();
        ring.observe("1:2", sample(0, true));
        ring.observe("1:2", sample(100, false));
        let delta = ring.read("1:2", 1);
        assert_eq!(delta.len(), 1);
        assert_eq!(delta[0].volume, None);
        assert_eq!(delta[0].tracking, "stale");
        ring.observe("1:2", sample(50, true));
        assert_eq!(ring.read("1:2", 0).len(), 2);
        assert!(ring.read("1:2", 2).is_empty());
    }

    #[test]
    fn invalid_tracking_and_source_transitions_inside_a_bucket_leave_a_gap() {
        let mut ring = History::default();
        ring.observe("1:2", sample(0, true));
        ring.observe("1:2", sample(50, false));
        ring.observe("1:2", sample(100, true));
        assert_eq!(ring.read("1:2", 1)[0].volume, None);
        ring.observe("1:2", sample(200, true));
        let mut other = sample(250, true);
        other.source = "controller";
        ring.observe("1:2", other);
        ring.observe("1:2", sample(300, true));
        assert_eq!(ring.read("1:2", 3)[0].volume, None);
        ring.observe("1:2", sample(400, true));
        assert_eq!(ring.read("1:2", 4)[0].volume, Some(0.4));
        let mut reset = sample(500, true);
        reset.input_generation = 2;
        ring.observe("1:2", reset);
        assert_eq!(ring.read("1:2", 5)[0].volume, None);
    }
}
