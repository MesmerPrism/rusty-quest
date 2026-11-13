//! Closed, platform-independent policy for stopped-source diagnostic review.
use std::sync::Mutex;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum State {
    Live,
    Retiring,
    Ready,
    Held,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct ExternalRetirement {
    pub receipt_sha256: String,
    pub accepted_stop: bool,
    pub no_pending_rpc: bool,
    pub helper_eof_reaped_absent: bool,
    pub controller_eof_reaped_absent: bool,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Eye {
    pub generation: u64,
    pub sequence: u64,
    pub eye: usize,
    pub source_frame: u64,
    pub timestamp_ns: i64,
    pub render_frame: u64,
    pub width: u32,
    pub height: u32,
    pub format: i32,
    pub bytes: u64,
    pub sha256: String,
}
#[derive(Clone, Debug)]
pub(crate) struct Pair {
    pub eyes: [Eye; 2],
}
impl Pair {
    pub fn checked(eyes: Vec<Eye>) -> Option<Self> {
        let eyes: [Eye; 2] = eyes.try_into().ok()?;
        let a = &eyes[0];
        let b = &eyes[1];
        if a.eye != 0
            || b.eye != 1
            || a.generation == 0
            || a.sequence != 2
            || a.source_frame == 0
            || a.timestamp_ns <= 0
            || (
                a.generation,
                a.sequence,
                a.source_frame,
                a.timestamp_ns,
                a.render_frame,
                a.width,
                a.height,
                a.format,
            ) != (
                b.generation,
                b.sequence,
                b.source_frame,
                b.timestamp_ns,
                b.render_frame,
                b.width,
                b.height,
                b.format,
            )
            || !matches!(a.format, 37 | 44)
            || a.width == 0
            || a.height == 0
            || a.width > 4096
            || a.height > 4096
            || eyes.iter().any(|e| {
                e.bytes != u64::from(e.width) * u64::from(e.height) * 4 || !digest(&e.sha256)
            })
        {
            return None;
        }
        Some(Self { eyes })
    }
}
pub fn digest(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
#[derive(Clone, Copy, Debug, Default)]
pub(crate) enum JavaCleanup {
    #[default]
    NotAttempted,
    Pending,
    Complete,
    Unknown,
}
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct Drain {
    pub java_cleanup: JavaCleanup,
    pub gpu_idle: bool,
    pub recordings_reset: bool,
    pub callbacks_sealed: bool,
    pub acquired_images: usize,
    pub image_refs: usize,
    pub imports: usize,
    pub pending_exports: usize,
    pub pending_releases: usize,
}
pub(crate) struct Policy {
    pub generation: u64,
    pub state: State,
    teardown_decision: Option<bool>,
}
impl Policy {
    pub fn new(generation: u64) -> Self {
        Self {
            generation,
            state: State::Live,
            teardown_decision: None,
        }
    }
    pub fn begin(&mut self, generation: u64, e: &ExternalRetirement) -> bool {
        if self.teardown_decision.is_some()
            || self.state != State::Live
            || generation == 0
            || generation != self.generation
            || !digest(&e.receipt_sha256)
            || !e.accepted_stop
            || !e.no_pending_rpc
            || !e.helper_eof_reaped_absent
            || !e.controller_eof_reaped_absent
        {
            return false;
        }
        self.state = State::Retiring;
        true
    }
    /// The renderer owns this decision before any teardown can destroy resources.
    pub fn begin_teardown(&mut self) -> bool {
        *self
            .teardown_decision
            .get_or_insert(matches!(self.state, State::Retiring))
    }
    pub fn finish(&mut self, d: Drain, pair: Option<&Pair>) -> bool {
        if self.state != State::Retiring {
            return false;
        }
        let ready = self.teardown_decision == Some(true)
            && matches!(d.java_cleanup, JavaCleanup::Complete)
            && d.gpu_idle
            && d.recordings_reset
            && d.callbacks_sealed
            && d.acquired_images == 0
            && d.image_refs == 0
            && d.imports == 0
            && d.pending_exports == 0
            && d.pending_releases == 0
            && pair.is_some_and(|p| p.eyes[0].generation == self.generation);
        self.state = if ready { State::Ready } else { State::Held };
        ready
    }
    pub fn admissible_generation(&self) -> u64 {
        if self.state == State::Live && self.teardown_decision.is_none() {
            self.generation
        } else {
            0
        }
    }
}
#[derive(Default)]
pub(crate) struct AcquisitionGate(Mutex<bool>);
/// Reset every original recording only after owner-wide idle. No fence state is inferred.
pub(crate) fn reset_recordings_after_idle(
    idle: bool,
    slots: usize,
    mut reset: impl FnMut(usize) -> bool,
) -> bool {
    idle && slots > 0 && (0..slots).all(|slot| reset(slot))
}
impl AcquisitionGate {
    pub fn run<R>(&self, f: impl FnOnce() -> R) -> Option<R> {
        let sealed = self.0.lock().ok()?;
        if *sealed { None } else { Some(f()) }
    }
    pub fn seal(&self) {
        if let Ok(mut sealed) = self.0.lock() {
            *sealed = true;
        }
    }
    pub fn sealed(&self) -> bool {
        self.0.lock().map(|s| *s).unwrap_or(false)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn external() -> ExternalRetirement {
        ExternalRetirement {
            receipt_sha256: "a".repeat(64),
            accepted_stop: true,
            no_pending_rpc: true,
            helper_eof_reaped_absent: true,
            controller_eof_reaped_absent: true,
        }
    }
    fn eye(n: usize) -> Eye {
        Eye {
            generation: 7,
            sequence: 2,
            eye: n,
            source_frame: 42,
            timestamp_ns: 900,
            render_frame: 100,
            width: 2,
            height: 2,
            format: 37,
            bytes: 16,
            sha256: "b".repeat(64),
        }
    }
    fn pair() -> Pair {
        Pair::checked(vec![eye(0), eye(1)]).expect("same completed pair")
    }
    fn drained() -> Drain {
        Drain {
            java_cleanup: JavaCleanup::Complete,
            gpu_idle: true,
            recordings_reset: true,
            callbacks_sealed: true,
            ..Drain::default()
        }
    }
    #[test]
    fn teardown_closes_admission_before_decision_to_destruction_interleavings() {
        let mut before = Policy::new(7);
        assert!(before.begin(7, &external()));
        assert!(before.begin_teardown());
        assert!(before.finish(drained(), Some(&pair())));
        for after_destruction in [false, true] {
            let mut late = Policy::new(7);
            let selected = late.begin_teardown();
            assert!(!selected);
            if after_destruction {
                assert!(reset_recordings_after_idle(true, 2, |_| true));
            }
            assert!(
                !late.begin(7, &external()),
                "late request must never acquire retirement"
            );
            assert!(!late.finish(drained(), Some(&pair())));
            assert!(!matches!(late.state, State::Ready));
        }
    }
    #[test]
    fn final_receipt_requires_the_latched_teardown_decision() {
        let mut p = Policy::new(7);
        assert!(p.begin(7, &external()));
        assert!(!p.finish(drained(), Some(&pair())));
    }
    #[test]
    fn incomplete_original_java_cleanup_prevents_receipt_and_exposure() {
        for result in [
            JavaCleanup::NotAttempted,
            JavaCleanup::Pending,
            JavaCleanup::Unknown,
        ] {
            let mut p = Policy::new(7);
            assert!(p.begin(7, &external()));
            assert!(p.begin_teardown());
            let mut d = drained();
            d.java_cleanup = result;
            assert!(!p.finish(d, Some(&pair())));
            assert!(matches!(p.state, State::Held));
        }
    }
    #[test]
    fn retirement_permanently_invalidates_cached_generation() {
        let mut p = Policy::new(7);
        assert!(!p.begin(6, &external()));
        assert!(p.begin(7, &external()));
        assert_eq!(p.admissible_generation(), 0);
        assert!(p.begin_teardown());
        assert!(p.finish(drained(), Some(&pair())));
        assert_eq!(p.state, State::Ready);
        assert!(!p.begin(7, &external()));
        assert_eq!(p.admissible_generation(), 0);
    }
    #[test]
    fn missing_stop_or_original_process_retirement_cannot_seal() {
        for n in 0..5 {
            let mut e = external();
            match n {
                0 => e.accepted_stop = false,
                1 => e.no_pending_rpc = false,
                2 => e.helper_eof_reaped_absent = false,
                3 => e.controller_eof_reaped_absent = false,
                _ => e.receipt_sha256 = "wrong".into(),
            };
            let mut p = Policy::new(7);
            assert!(!p.begin(7, &e));
            assert_eq!(p.state, State::Live);
        }
    }
    #[test]
    fn idle_unknown_or_retained_work_holds_review_even_with_complete_pixels() {
        for n in 0..8 {
            let mut d = drained();
            match n {
                0 => d.gpu_idle = false,
                1 => d.recordings_reset = false,
                2 => d.callbacks_sealed = false,
                3 => d.acquired_images = 1,
                4 => d.image_refs = 1,
                5 => d.imports = 1,
                6 => d.pending_exports = 1,
                _ => d.pending_releases = 1,
            };
            let mut p = Policy::new(7);
            assert!(p.begin(7, &external()));
            assert!(p.begin_teardown());
            assert!(!p.finish(d, Some(&pair())));
            assert_eq!(p.state, State::Held);
            assert!(!p.finish(drained(), Some(&pair())));
        }
    }
    #[test]
    fn unsubmitted_slot_needs_recording_reset_not_an_unsignalled_fence() {
        let mut reset = Vec::new();
        // All three original slots must reset, including an unsubmitted slot. There is no fence wait.
        assert!(reset_recordings_after_idle(true, 3, |slot| {
            reset.push(slot);
            true
        }));
        assert_eq!(reset, vec![0, 1, 2]);
        assert!(!reset_recordings_after_idle(false, 3, |_| panic!(
            "unknown idle must not reset"
        )));
        reset.clear();
        assert!(!reset_recordings_after_idle(true, 3, |slot| {
            reset.push(slot);
            slot != 1
        }));
        assert_eq!(reset, vec![0, 1]);
    }
    #[test]
    fn one_eye_mixed_frame_generation_or_byte_shape_is_not_an_output_pair() {
        assert!(Pair::checked(vec![eye(0)]).is_none());
        for n in 0..8 {
            let mut b = eye(1);
            match n {
                0 => b.generation = 8,
                1 => b.source_frame = 43,
                2 => b.timestamp_ns = 901,
                3 => b.render_frame = 101,
                4 => b.eye = 0,
                5 => b.sequence = 3,
                6 => b.bytes = 15,
                _ => b.format = 43,
            };
            assert!(Pair::checked(vec![eye(0), b]).is_none());
        }
        assert!(Pair::checked(vec![eye(0), eye(1)]).is_some());
    }
    #[test]
    fn sealed_callbacks_never_acquire_or_publish_again() {
        let g = AcquisitionGate::default();
        assert!(!g.sealed());
        let mut acquired = 0;
        g.run(|| acquired += 1);
        g.seal();
        assert!(g.sealed());
        assert!(g.run(|| acquired += 1).is_none());
        assert_eq!(acquired, 1);
    }
    #[test]
    fn callback_keeps_the_seal_mutex_until_its_resource_body_finishes() {
        use std::sync::{Arc, mpsc};
        let gate = Arc::new(AcquisitionGate::default());
        let worker_gate = gate.clone();
        let (entered_tx, entered_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let worker = std::thread::spawn(move || {
            worker_gate.run(|| {
                entered_tx.send(()).unwrap();
                release_rx.recv().unwrap();
            })
        });
        entered_rx.recv().unwrap();
        assert!(gate.0.try_lock().is_err());
        release_tx.send(()).unwrap();
        worker.join().unwrap();
        gate.seal();
        assert!(gate.run(|| ()).is_none());
    }
    #[test]
    fn a_complete_other_generation_pair_cannot_qualify_this_app_drain() {
        let mut eyes = vec![eye(0), eye(1)];
        for eye in &mut eyes {
            eye.generation = 8;
        }
        let other = Pair::checked(eyes).unwrap();
        let mut p = Policy::new(7);
        assert!(p.begin(7, &external()));
        assert!(p.begin_teardown());
        assert!(!p.finish(drained(), Some(&other)));
        assert_eq!(p.state, State::Held);
    }
}
