//! Completion policy for four durable exports collected after one frame fence.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Identity {
    pub generation: u64,
    pub sequence: u64,
    pub input_frame: u64,
    pub timestamp_ns: i64,
    pub render_frame: u64,
}
#[derive(Clone, Copy, Debug)]
pub(crate) enum Stage {
    RawPack,
    Processed,
}
pub(crate) struct PairCompletion {
    identity: Identity,
    written: u8,
}
impl PairCompletion {
    pub(crate) fn after_retired_fence(identity: Identity) -> Self {
        Self {
            identity,
            written: 0,
        }
    }
    pub(crate) fn mark_durable(&mut self, identity: Identity, eye: usize, stage: Stage) -> bool {
        if identity != self.identity || eye > 1 {
            return false;
        }
        let bit = eye
            + if matches!(stage, Stage::Processed) {
                2
            } else {
                0
            };
        self.written |= 1 << bit;
        true
    }
    pub(crate) fn complete(&self) -> bool {
        self.written == 0b1111
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn id() -> Identity {
        Identity {
            generation: 7,
            sequence: 2,
            input_frame: 42,
            timestamp_ns: 1234,
            render_frame: 99,
        }
    }
    #[test]
    fn processed_eyes_alone_never_publish_paired_completion() {
        let mut pair = PairCompletion::after_retired_fence(id());
        for eye in 0..2 {
            assert!(pair.mark_durable(id(), eye, Stage::Processed));
        }
        assert!(!pair.complete(), "both raw inputs must be durable as well");
    }
    #[test]
    fn every_missing_or_failed_export_suppresses_completion() {
        for missing in 0..4 {
            let mut pair = PairCompletion::after_retired_fence(id());
            for index in 0..4 {
                if index != missing {
                    assert!(pair.mark_durable(
                        id(),
                        index % 2,
                        if index < 2 {
                            Stage::RawPack
                        } else {
                            Stage::Processed
                        }
                    ));
                }
            }
            assert!(!pair.complete(), "failed/missing export {missing}");
        }
        let mut pair = PairCompletion::after_retired_fence(id());
        for stage in [Stage::RawPack, Stage::Processed] {
            for eye in 0..2 {
                pair.mark_durable(id(), eye, stage);
            }
        }
        assert!(pair.complete());
    }
    #[test]
    fn mixed_source_render_or_generation_cannot_complete_pair() {
        for changed in 0..5 {
            let mut wrong = id();
            match changed {
                0 => wrong.generation += 1,
                1 => wrong.sequence += 1,
                2 => wrong.input_frame += 1,
                3 => wrong.timestamp_ns += 1,
                _ => wrong.render_frame += 1,
            }
            let mut pair = PairCompletion::after_retired_fence(id());
            assert!(!pair.mark_durable(wrong, 0, Stage::RawPack));
            assert!(!pair.mark_durable(id(), 2, Stage::Processed));
            assert!(!pair.complete());
        }
    }
}
