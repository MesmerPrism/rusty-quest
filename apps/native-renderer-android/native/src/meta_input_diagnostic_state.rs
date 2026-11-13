//! Closed control state shared by the private Surface bridge and frame boundary.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum Effect {
    Raw,
    Colorama,
    FullCore3,
}
impl Effect {
    pub(crate) fn code(self) -> u32 {
        match self {
            Self::Raw => 0,
            Self::Colorama => 1,
            Self::FullCore3 => 2,
        }
    }
}

#[derive(Debug)]
pub(crate) struct Control {
    pub(crate) generation: u64,
    pub(crate) active: bool,
    pub(crate) effect: Effect,
    pub(crate) sequence: u64,
    allow_full_core3: bool,
}

impl Control {
    pub(crate) fn new(generation: u64) -> Self {
        Self::new_with_full_core3(generation, false)
    }
    pub(crate) fn new_with_full_core3(generation: u64, allow_full_core3: bool) -> Self {
        assert!(generation > 0 && generation <= i64::MAX as u64);
        Self {
            generation,
            active: true,
            effect: Effect::Raw,
            sequence: 0,
            allow_full_core3,
        }
    }
    pub(crate) fn select(&mut self, generation: u64, mode: i32, sequence: u64) -> bool {
        let effect = match mode {
            0 => Effect::Raw,
            1 => Effect::Colorama,
            2 if self.allow_full_core3 => Effect::FullCore3,
            _ => return false,
        };
        if !self.active || generation != self.generation || sequence <= self.sequence {
            return false;
        }
        self.effect = effect;
        self.sequence = sequence;
        true
    }
    pub(crate) fn stop(&mut self) {
        self.active = false;
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct WitnessTiming {
    pub(crate) elapsed_ms: u64,
    pub(crate) input_advances: u64,
    pub(crate) first_timestamp_ns: i64,
}

pub(crate) fn witness_timing_if_enabled(
    save_witnesses: bool,
    ready: impl FnOnce() -> Option<WitnessTiming>,
) -> Option<WitnessTiming> {
    if save_witnesses { ready() } else { None }
}

pub(crate) fn require_witness_saving(save_witnesses: bool) -> Result<(), String> {
    if save_witnesses {
        Ok(())
    } else {
        Err("disabled-no-save: pixel witness saving disabled by startup config".into())
    }
}

#[derive(Default)]
pub(crate) struct WitnessCadence {
    phase: Option<(u64, u64, u64, i64, i64, u64)>,
}
impl WitnessCadence {
    pub(crate) const fn new() -> Self {
        Self { phase: None }
    }
    pub(crate) fn observe(&mut self, generation: u64, sequence: u64, timestamp: i64, at_ms: u64) {
        if timestamp <= 0 {
            return;
        }
        if let Some((g, s, _, _, last, advances)) = self.phase.as_mut() {
            if *g == generation && sequence < *s {
                return;
            }
            if (*g, *s) == (generation, sequence) {
                if timestamp > *last {
                    *last = timestamp;
                    *advances = advances.saturating_add(1);
                }
                return;
            }
        }
        self.phase = Some((generation, sequence, at_ms, timestamp, timestamp, 0));
    }
    pub(crate) fn ready(
        &self,
        generation: u64,
        sequence: u64,
        now_ms: u64,
    ) -> Option<WitnessTiming> {
        let (g, s, first, first_timestamp_ns, _, input_advances) = self.phase?;
        let elapsed_ms = now_ms.saturating_sub(first);
        if (g, s) != (generation, sequence) || elapsed_ms < 1000 || input_advances < 3 {
            return None;
        }
        Some(WitnessTiming {
            elapsed_ms,
            input_advances,
            first_timestamp_ns,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn no_save_prevents_readback_admission_even_for_default_raw_sequence_zero() {
        let control = Control::new(7);
        assert_eq!((control.effect, control.sequence), (Effect::Raw, 0));
        let mut cadence = WitnessCadence::new();
        for (timestamp, at) in [(100, 0), (101, 100), (102, 200), (103, 300)] {
            cadence.observe(7, 0, timestamp, at);
        }
        assert!(cadence.ready(7, 0, 1000).is_some());
        assert!(
            witness_timing_if_enabled(false, || panic!(
                "disabled saving must not enter readback admission"
            ))
            .is_none()
        );
        assert!(witness_timing_if_enabled(true, || cadence.ready(7, 0, 1000)).is_some());
    }
    #[test]
    fn defensive_writers_cannot_admit_pixels_when_saving_is_disabled() {
        assert!(require_witness_saving(false).is_err());
        assert!(require_witness_saving(true).is_ok());
    }
    #[test]
    fn delayed_requests_cannot_change_a_newer_or_retired_generation() {
        let mut c = Control::new(71);
        assert!(!c.select(70, 1, 100));
        assert!(!c.select(71, 3, 1));
        assert_eq!(c.effect, Effect::Raw);
        assert!(c.select(71, 1, 2));
        assert!(!c.select(71, 0, 1));
        assert!(!c.select(71, 0, 2));
        assert_eq!(c.effect, Effect::Colorama);
        assert!(c.select(71, 0, 3));
        c.stop();
        assert!(!c.select(71, 1, 4));
        assert_eq!(c.effect, Effect::Raw);
    }
    #[test]
    fn full_graph_control_remains_generation_and_sequence_bound() {
        let mut unavailable = Control::new(92);
        assert!(!unavailable.select(92, 2, 1));
        assert_eq!(unavailable.effect, Effect::Raw);
        let mut c = Control::new_with_full_core3(92, true);
        assert!(c.select(92, 2, 1));
        assert_eq!(c.effect, Effect::FullCore3);
        assert_eq!(c.effect.code(), 2);
        assert!(!c.select(91, 0, 99));
        assert!(!c.select(92, 1, 1));
        assert!(!c.select(92, 3, 2));
        assert_eq!(c.effect, Effect::FullCore3);
        c.stop();
        assert!(!c.select(92, 2, 2));
    }
    #[test]
    fn witnesses_require_elapsed_time_and_new_completed_inputs_in_same_phase() {
        let mut c = WitnessCadence::new();
        c.observe(7, 2, 100, 1000);
        for t in [100, 100, 100] {
            c.observe(7, 2, t, 1200);
        }
        assert!(c.ready(7, 2, 2500).is_none()); // Repeated display frames do not advance input.
        for (t, at) in [(101, 1300), (102, 1400), (103, 1500)] {
            c.observe(7, 2, t, at);
        }
        assert!(c.ready(7, 2, 1999).is_none());
        assert_eq!(
            c.ready(7, 2, 2000),
            Some(WitnessTiming {
                elapsed_ms: 1000,
                input_advances: 3,
                first_timestamp_ns: 100
            })
        );
        assert!(c.ready(7, 3, 3000).is_none());
        c.observe(7, 3, 104, 3000);
        c.observe(7, 2, 999, 3500); // Late older phase cannot restart readiness.
        assert!(c.ready(7, 3, 5000).is_none());
        assert!(c.ready(8, 3, 5000).is_none());
    }
}
