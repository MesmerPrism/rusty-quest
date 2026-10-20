//! Readiness of one claimed source graph; liveness alone cannot admit reuse.
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::mpsc::{Receiver, RecvTimeoutError};
use std::time::{Duration, Instant};

/// Finite probes retain their explicit startup deadline. For an owned ongoing
/// graph this duration only schedules one warning; it never changes readiness.
pub(crate) const READY_WAIT: Duration = Duration::from_secs(15);

/// Observation cadence for an actual cancellation latch, not a completion limit.
const CANCEL_POLL: Duration = Duration::from_millis(50);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum OwnedReceiveError {
    Cancelled,
    Disconnected,
}

/// Observe the same issued worker's channel until its actual result or terminal
/// cancellation/disconnection. The caller retains and joins its original worker.
/// No owner mutex may be held across this wait. Warning publication is advisory.
pub(crate) fn wait_owned_receiver<T>(
    receiver: &Receiver<T>,
    estimate: Duration,
    cancelled: &AtomicBool,
    warning: impl FnOnce() -> Result<(), String>,
) -> Result<T, OwnedReceiveError> {
    let started = Instant::now();
    let mut warning = Some(warning);
    loop {
        if cancelled.load(Ordering::Acquire) {
            return Err(OwnedReceiveError::Cancelled);
        }
        let elapsed = started.elapsed();
        if elapsed >= estimate {
            if let Some(publish) = warning.take() {
                let _ = publish();
            }
            // A warning callback may expose an already requested actual Stop.
            if cancelled.load(Ordering::Acquire) {
                return Err(OwnedReceiveError::Cancelled);
            }
        }
        let poll = if warning.is_some() {
            CANCEL_POLL.min(estimate.saturating_sub(elapsed))
        } else {
            CANCEL_POLL
        };
        match receiver.recv_timeout(poll) {
            Ok(result) => {
                return if cancelled.load(Ordering::Acquire) {
                    Err(OwnedReceiveError::Cancelled)
                } else {
                    Ok(result)
                };
            }
            Err(RecvTimeoutError::Timeout) => continue,
            Err(RecvTimeoutError::Disconnected) => {
                return Err(OwnedReceiveError::Disconnected);
            }
        }
    }
}

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

    pub(crate) fn wait_owned(
        &self,
        receiver: &Receiver<Result<(), String>>,
        estimate: Duration,
        cancelled: &AtomicBool,
        warning: impl FnOnce() -> Result<(), String>,
    ) -> WaitOutcome {
        match wait_owned_receiver(receiver, estimate, cancelled, warning) {
            Ok(Ok(())) if self.state() == State::Ready => WaitOutcome::Ready,
            Ok(_) => {
                self.fail();
                if self.state() == State::Closed {
                    WaitOutcome::Closed
                } else {
                    WaitOutcome::Failed
                }
            }
            Err(_) => {
                self.close();
                if self.state() == State::Failed {
                    WaitOutcome::Failed
                } else {
                    WaitOutcome::Closed
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
    fn finite_probe_receives_actual_setup_within_selected_budget() {
        let ready = Arc::new(Readiness::default());
        let worker_ready = ready.clone();
        let (tx, rx) = mpsc::sync_channel(1);
        let worker = thread::spawn(move || {
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

    #[test]
    fn ongoing_estimate_warns_then_receives_same_gated_worker_even_if_warning_fails() {
        for publication_fails in [false, true] {
            let ready = Arc::new(Readiness::default());
            let worker_ready = ready.clone();
            let cancelled = AtomicBool::new(false);
            let (release_tx, release_rx) = mpsc::channel();
            let (tx, rx) = mpsc::sync_channel(1);
            let worker = thread::spawn(move || {
                release_rx.recv().unwrap();
                let published = worker_ready.publish_ready();
                tx.send(Ok(())).unwrap();
                published
            });
            let mut warning_count = 0;
            let mut observed_pending = false;
            let outcome = ready.wait_owned(&rx, Duration::ZERO, &cancelled, || {
                warning_count += 1;
                observed_pending = ready.state() == State::Pending && !worker.is_finished();
                // Always release this actual original before reporting sink failure.
                release_tx.send(()).unwrap();
                if publication_fails {
                    Err("modeled warning sink failure".into())
                } else {
                    Ok(())
                }
            });
            let published = worker.join().unwrap();
            assert_eq!(outcome, WaitOutcome::Ready);
            assert_eq!(warning_count, 1);
            assert!(observed_pending && published);
            assert!(ready.reusable(true, true, false));
        }
    }

    #[test]
    fn ongoing_stop_after_warning_closes_before_late_worker_publication_and_real_join() {
        let ready = Arc::new(Readiness::default());
        let worker_ready = ready.clone();
        let cancelled = Arc::new(AtomicBool::new(false));
        let stop_cancelled = cancelled.clone();
        let (stop_tx, stop_rx) = mpsc::channel();
        let stop_owner = thread::spawn(move || {
            stop_rx.recv().unwrap();
            stop_cancelled.store(true, Ordering::Release);
        });
        let (release_tx, release_rx) = mpsc::channel();
        let (tx, rx) = mpsc::sync_channel(1);
        let worker = thread::spawn(move || {
            release_rx.recv().unwrap();
            let published = worker_ready.publish_ready();
            // The original receiver remains held until this actual worker joins.
            tx.send(Ok(())).unwrap();
            published
        });
        let outcome = ready.wait_owned(&rx, Duration::ZERO, &cancelled, || {
            // An independent actual Stop owner can reach the latch while this
            // original renderer remains gated and the waiting caller holds no lock.
            stop_tx.send(()).unwrap();
            Ok(())
        });
        release_tx.send(()).unwrap();
        let published = worker.join().unwrap();
        stop_owner.join().unwrap();
        assert_eq!(outcome, WaitOutcome::Closed);
        assert!(!published);
        assert_eq!(ready.state(), State::Closed);
    }

    #[test]
    fn ongoing_actual_worker_error_and_disconnect_remain_terminal_after_warning() {
        for actual_error in [true, false] {
            let ready = Readiness::default();
            let cancelled = AtomicBool::new(false);
            let (release_tx, release_rx) = mpsc::channel();
            let (tx, rx) = mpsc::sync_channel(1);
            let worker = thread::spawn(move || {
                release_rx.recv().unwrap();
                if actual_error {
                    tx.send(Err("actual modeled worker error".into())).unwrap();
                }
            });
            let outcome = ready.wait_owned(&rx, Duration::ZERO, &cancelled, || {
                release_tx.send(()).unwrap();
                Ok(())
            });
            worker.join().unwrap();
            assert_eq!(
                outcome,
                if actual_error {
                    WaitOutcome::Failed
                } else {
                    WaitOutcome::Closed
                }
            );
            assert!(!ready.publish_ready());
        }
    }

    #[test]
    fn ongoing_unpublished_or_terminal_queued_readiness_never_admits() {
        for state in [State::Pending, State::Failed, State::Closed] {
            let ready = Readiness::default();
            match state {
                State::Failed => ready.fail(),
                State::Closed => ready.close(),
                _ => {}
            }
            let cancelled = AtomicBool::new(false);
            let (tx, rx) = mpsc::sync_channel(1);
            tx.send(Ok(())).unwrap();
            let outcome = ready.wait_owned(&rx, Duration::ZERO, &cancelled, || Ok(()));
            assert_eq!(
                outcome,
                if state == State::Closed {
                    WaitOutcome::Closed
                } else {
                    WaitOutcome::Failed
                }
            );
        }
    }

    #[test]
    fn ongoing_cancelled_queued_ready_is_closed_without_warning_or_reviving_worker() {
        let ready = Readiness::default();
        assert!(ready.publish_ready());
        let cancelled = AtomicBool::new(true);
        let (tx, rx) = mpsc::sync_channel(1);
        tx.send(Ok(())).unwrap();
        let mut warned = false;
        assert_eq!(
            ready.wait_owned(&rx, Duration::ZERO, &cancelled, || {
                warned = true;
                Ok(())
            }),
            WaitOutcome::Closed
        );
        assert!(!warned && !ready.publish_ready());
    }

    #[test]
    fn shared_receiver_preserves_exact_worker_error_payload_without_weakening_result() {
        let cancelled = AtomicBool::new(false);
        let (tx, rx) = mpsc::sync_channel(1);
        tx.send(Err::<(), _>("exact actual error payload".to_string()))
            .unwrap();
        assert_eq!(
            wait_owned_receiver(&rx, Duration::ZERO, &cancelled, || Ok(())),
            Ok(Err("exact actual error payload".to_string()))
        );
    }
}
