//! Monotonic, source-neutral four-phase pacer; never depends on UI timers.
use crate::bounded_breath_phase_integrator::{BoundedBreathPhaseIntegrator, BreathHoldPolicy};
use rusty_quest_breath_contract::assessment::CommonBreathPhase;

#[derive(Clone, Copy, Debug)]
pub(crate) struct AutomaticBreath {
    started_ns: u64,
    last_ns: u64,
    value: BoundedBreathPhaseIntegrator,
}

impl AutomaticBreath {
    pub(crate) fn new(now_ns: u64, progress: f32) -> Self {
        Self {
            started_ns: now_ns,
            last_ns: now_ns,
            value: BoundedBreathPhaseIntegrator::new(progress),
        }
    }
    pub(crate) fn phase(&self, now_ns: u64) -> &'static str {
        match now_ns.saturating_sub(self.started_ns) / 4_000_000_000 % 4 {
            0 => "inhale",
            1 => "full-hold",
            2 => "exhale",
            _ => "empty-hold",
        }
    }
    pub(crate) fn advance(&mut self, now_ns: u64) -> f32 {
        if now_ns <= self.last_ns {
            return self.value.value01();
        }
        let mut from = self.last_ns.saturating_sub(self.started_ns);
        let to = now_ns.saturating_sub(self.started_ns);
        // Every completed full cycle reaches empty. Skip arbitrarily long
        // suspension in O(1), then integrate at most one cycle plus its prefix.
        let cycle = 16_000_000_000_u64;
        if to / cycle > from / cycle {
            self.value.reset(0.0);
            from = to / cycle * cycle;
        }
        while from < to {
            let end = to.min((from / 1_000_000_000 + 1) * 1_000_000_000);
            let phase = match from / 4_000_000_000 % 4 {
                0 => CommonBreathPhase::Inhale,
                2 => CommonBreathPhase::Exhale,
                _ => CommonBreathPhase::Hold,
            };
            self.value.update(
                phase,
                (end - from) as f32 / 1e9,
                0.25,
                0.25,
                BreathHoldPolicy::Hold,
                0.0,
            );
            from = end;
        }
        self.last_ns = now_ns;
        self.value.value01()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn initial_partial_inhale_keeps_full_phase_and_cycle() {
        let mut pacer = AutomaticBreath::new(0, 0.5);
        assert_eq!(pacer.advance(2_000_000_000), 1.0);
        assert_eq!(pacer.phase(3_000_000_000), "inhale");
        assert_eq!(pacer.advance(4_000_000_000), 1.0);
        assert_eq!(pacer.phase(4_000_000_000), "full-hold");
        assert_eq!(pacer.advance(8_000_000_000), 1.0);
        assert_eq!(pacer.advance(12_000_000_000), 0.0);
        assert_eq!(pacer.phase(12_000_000_000), "empty-hold");
        assert_eq!(pacer.advance(16_000_000_000), 0.0);
        assert_eq!(pacer.advance(18_000_000_000), 0.5);
    }
    #[test]
    fn long_suspend_is_bounded_and_backward_time_does_not_rewind() {
        let mut pacer = AutomaticBreath::new(10, 0.7);
        assert_eq!(pacer.advance(16_000_000_000_000_010 + 2_000_000_000), 0.5);
        assert_eq!(pacer.advance(0), 0.5);
    }
}
