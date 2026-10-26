//! Readiness of one claimed source graph; liveness alone cannot admit reuse.
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::mpsc::{Receiver, RecvTimeoutError};
use std::time::Duration;

/// Fits alongside the existing ten-second Java freshness stage inside its
/// thirty-second serialized activation envelope. Physical join is never timed out.
pub(crate) const READY_WAIT: Duration = Duration::from_secs(15);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum State {
    Pending,
    Ready,
    Failed,
    Closed,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum WaitOutcome {
    Ready,
    Failed,
    Closed,
    Deadline,
}

impl WaitOutcome {
    pub(crate) fn marker(self) -> &'static str {
        match self {
            Self::Ready => "ready",
            Self::Failed => "failed",
            Self::Closed => "closed",
            Self::Deadline => "deadline",
        }
    }
}

#[derive(Default)]
pub(crate) struct Readiness(AtomicU8);

impl Readiness {
    pub(crate) fn state(&self) -> State {
        match self.0.load(Ordering::Acquire) {
            0 => State::Pending,
            1 => State::Ready,
            2 => State::Failed,
            _ => State::Closed,
        }
    }

    /// Only actual renderer setup may publish this transition. Cancellation or
    /// retirement wins permanently; a late readiness signal cannot revive it.
    pub(crate) fn publish_ready(&self) -> bool {
        self.0
            .compare_exchange(0, 1, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
    }

    fn terminal(&self, terminal: u8) {
        let mut value = self.0.load(Ordering::Acquire);
        while value < 2 {
            match self
                .0
                .compare_exchange_weak(value, terminal, Ordering::AcqRel, Ordering::Acquire)
            {
                Ok(_) => return,
                Err(current) => value = current,
            }
        }
    }

    pub(crate) fn fail(&self) {
        self.terminal(2);
    }
    pub(crate) fn close(&self) {
        self.terminal(3);
    }

    pub(crate) fn reusable(&self, same_carrier: bool, running: bool, cancelled: bool) -> bool {
        same_carrier && running && !cancelled && self.state() == State::Ready
    }

    pub(crate) fn wait(
        &self,
        receiver: &Receiver<Result<(), String>>,
        budget: Duration,
    ) -> WaitOutcome {
        match receiver.recv_timeout(budget) {
            Ok(Ok(())) if self.state() == State::Ready => WaitOutcome::Ready,
            Ok(_) => {
                self.fail();
                if self.state() == State::Closed {
                    WaitOutcome::Closed
                } else {
                    WaitOutcome::Failed
                }
            }
            Err(RecvTimeoutError::Disconnected) => {
                self.close();
                if self.state() == State::Failed {
                    WaitOutcome::Failed
                } else {
                    WaitOutcome::Closed
                }
            }
            Err(RecvTimeoutError::Timeout) => {
                self.fail();
                if self.state() == State::Closed {
                    WaitOutcome::Closed
                } else {
                    WaitOutcome::Deadline
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{mpsc, Arc, Barrier};
    use std::thread;

    #[test]
    fn live_worker_is_pending_until_actual_setup_and_exact_reuse_conditions_hold() {
        let ready = Readiness::default();
        assert_eq!(ready.state(), State::Pending);
        assert!(!ready.reusable(true, true, false));
        assert!(ready.publish_ready());
        assert!(ready.reusable(true, true, false));
        for (same, running, cancelled) in [
            (false, true, false),
            (true, false, false),
            (true, true, true),
        ] {
            assert!(!ready.reusable(same, running, cancelled));
        }
        ready.close();
        assert_eq!(ready.state(), State::Closed);
        assert!(!ready.publish_ready());
        assert!(!ready.reusable(true, true, false));
    }

    #[test]
    fn delayed_real_setup_is_received_within_selected_budget() {
        let ready = Arc::new(Readiness::default());
        let worker_ready = ready.clone();
        let (tx, rx) = mpsc::sync_channel(1);
        let worker = thread::spawn(move || {
            thread::sleep(Duration::from_millis(30));
            assert!(worker_ready.publish_ready());
            tx.send(Ok(())).unwrap();
        });
        assert_eq!(ready.wait(&rx, Duration::from_secs(2)), WaitOutcome::Ready);
        worker.join().unwrap();
        assert_eq!(READY_WAIT, Duration::from_secs(15));
    }

    #[test]
    fn deadline_cannot_be_changed_by_late_success_during_the_required_join() {
        let ready = Arc::new(Readiness::default());
        let worker_ready = ready.clone();
        let release = Arc::new(Barrier::new(2));
        let worker_release = release.clone();
        let (tx, rx) = mpsc::sync_channel(1);
        let worker = thread::spawn(move || {
            worker_release.wait();
            assert!(!worker_ready.publish_ready());
            // Receiver deliberately remains alive through actual worker join.
            tx.send(Ok(())).unwrap();
            worker_ready.close();
        });
        assert_eq!(ready.wait(&rx, Duration::ZERO), WaitOutcome::Deadline);
        release.wait();
        worker.join().unwrap();
        assert_eq!(ready.state(), State::Failed);
        assert!(!ready.reusable(true, true, false));
    }

    #[test]
    fn failure_or_closed_worker_never_turns_a_queued_ready_signal_into_admission() {
        for failed in [true, false] {
            let ready = Readiness::default();
            let (tx, rx) = mpsc::sync_channel(1);
            assert!(ready.publish_ready());
            tx.send(Ok(())).unwrap();
            if failed {
                ready.fail();
            } else {
                ready.close();
            }
            assert_eq!(
                ready.wait(&rx, Duration::ZERO),
                if failed {
                    WaitOutcome::Failed
                } else {
                    WaitOutcome::Closed
                }
            );
            assert!(!ready.reusable(true, true, false));
        }
    }

    #[test]
    fn missing_or_failed_setup_is_distinct_from_an_unpublished_ready_message() {
        let ready = Readiness::default();
        let (tx, rx) = mpsc::sync_channel(1);
        tx.send(Ok(())).unwrap();
        assert_eq!(ready.wait(&rx, Duration::ZERO), WaitOutcome::Failed);
        assert_eq!(ready.state(), State::Failed);
        let ready = Readiness::default();
        let (tx, rx) = mpsc::sync_channel(1);
        drop(tx);
        assert_eq!(ready.wait(&rx, Duration::ZERO), WaitOutcome::Closed);
        let ready = Readiness::default();
        let (tx, rx) = mpsc::sync_channel(1);
        tx.send(Err("modeled setup failure".into())).unwrap();
        assert_eq!(ready.wait(&rx, Duration::ZERO), WaitOutcome::Failed);
    }
}
