//! Same-worker dormant WSI gate; no frame/media authorization is created here.
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum Phase {
    Preparing,
    Ready,
    Committed,
    CleanupPending,
    Terminal,
}
struct State {
    phase: Phase,
    retry: u64,
    failure: Option<String>,
    extent: Option<(u32, u32)>,
}
pub(super) struct Gate {
    state: Mutex<State>,
    changed: Condvar,
    pub(super) stop: Arc<AtomicBool>,
}
impl Gate {
    pub(super) fn new(stop: Arc<AtomicBool>) -> Self {
        Self {
            state: Mutex::new(State {
                phase: Phase::Preparing,
                retry: 0,
                failure: None,
                extent: None,
            }),
            changed: Condvar::new(),
            stop,
        }
    }
    pub(super) fn snapshot(&self) -> (Phase, Option<String>) {
        let s = self.state.lock().unwrap_or_else(|e| e.into_inner());
        (s.phase, s.failure.clone())
    }
    pub(super) fn ready(&self, extent: (u32, u32)) -> Result<(), String> {
        let mut s = self.state.lock().map_err(|_| "prepared gate poisoned")?;
        if self.stop.load(Ordering::Acquire) || s.phase != Phase::Preparing {
            return Err("prepared renderer stopped before readiness".into());
        }
        s.extent = Some(extent);
        s.phase = Phase::Ready;
        self.changed.notify_all();
        Ok(())
    }
    pub(super) fn extent(&self) -> Option<(u32, u32)> {
        self.state.lock().unwrap_or_else(|e| e.into_inner()).extent
    }
    pub(super) fn commit(&self) -> Result<(), String> {
        let mut s = self.state.lock().map_err(|_| "prepared gate poisoned")?;
        if self.stop.load(Ordering::Acquire) || s.phase != Phase::Ready {
            return Err("prepared renderer not ready or already consumed".into());
        }
        s.phase = Phase::Committed;
        self.changed.notify_all();
        Ok(())
    }
    pub(super) fn abort(&self) {
        self.stop.store(true, Ordering::Release);
        let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
        s.retry = s
            .retry
            .checked_add(1)
            .expect("prepared cleanup retry ordinal overflow");
        self.changed.notify_all();
    }
    pub(super) fn wait_commit(&self, mut current: impl FnMut() -> bool) -> Result<(), String> {
        loop {
            if self.stop.load(Ordering::Acquire) || !current() {
                return Err("prepared renderer reservation/carrier changed".into());
            }
            let s = self.state.lock().map_err(|_| "prepared gate poisoned")?;
            if s.phase == Phase::Committed {
                return Ok(());
            }
            if s.phase != Phase::Ready {
                return Err("prepared renderer gate is not dormant ready".into());
            }
            // Polling exists only to observe the actual same-owner Stop/source change.
            // There is no elapsed acceptance/refusal deadline.
            drop(
                self.changed
                    .wait_timeout(s, Duration::from_millis(20))
                    .map_err(|_| "prepared gate poisoned")?,
            );
        }
    }
    pub(super) fn cleanup_failed(&self, error: String) -> u64 {
        let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
        s.phase = Phase::CleanupPending;
        s.failure = Some(error);
        self.changed.notify_all();
        s.retry
    }
    pub(super) fn wait_cleanup_retry(&self, previous: u64) {
        let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
        while s.retry == previous {
            s = self.changed.wait(s).unwrap_or_else(|e| e.into_inner());
        }
    }
    pub(super) fn terminal(&self, error: Option<String>) {
        let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
        s.phase = Phase::Terminal;
        s.failure = error;
        self.changed.notify_all();
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn dormant_worker_waits_for_real_commit_without_frames() {
        let g = Arc::new(Gate::new(Arc::new(AtomicBool::new(false))));
        g.ready((2048, 1024)).unwrap();
        let (done_tx, done_rx) = std::sync::mpsc::channel();
        let w = g.clone();
        let worker = std::thread::spawn(move || {
            w.wait_commit(|| true).unwrap();
            done_tx.send(1).unwrap();
        });
        assert_eq!(g.snapshot().0, Phase::Ready);
        assert!(done_rx.try_recv().is_err());
        g.commit().unwrap();
        assert_eq!(done_rx.recv().unwrap(), 1);
        worker.join().unwrap();
        assert!(g.commit().is_err());
    }
    #[test]
    fn actual_stop_unwinds_same_dormant_worker() {
        let g = Arc::new(Gate::new(Arc::new(AtomicBool::new(false))));
        g.ready((2048, 1024)).unwrap();
        let w = g.clone();
        let worker = std::thread::spawn(move || w.wait_commit(|| true));
        g.abort();
        assert!(worker.join().unwrap().is_err());
        assert!(g.commit().is_err());
    }
    #[test]
    fn changed_source_cannot_commit_resource_use() {
        let g = Gate::new(Arc::new(AtomicBool::new(false)));
        g.ready((2048, 1024)).unwrap();
        assert!(g.wait_commit(|| false).is_err());
    }
    #[test]
    fn failed_cleanup_retains_worker_until_actual_retry() {
        let g = Arc::new(Gate::new(Arc::new(AtomicBool::new(false))));
        let n = g.cleanup_failed("modeled actual fence pending".into());
        let w = g.clone();
        let worker = std::thread::spawn(move || {
            w.wait_cleanup_retry(n);
            w.terminal(None)
        });
        assert_eq!(g.snapshot().0, Phase::CleanupPending);
        g.abort();
        worker.join().unwrap();
        assert_eq!(g.snapshot().0, Phase::Terminal);
    }
}

/// Only WSI construction allocations before any queue submit/import/present.
/// Each actual handle's destructor is installed immediately after creation.
/// Reverse drop completes before an Err can report construction terminal.
pub(super) struct ConstructionRollback {
    actions: Vec<Box<dyn FnOnce()>>,
}
impl ConstructionRollback {
    pub(super) fn new() -> Self {
        Self {
            actions: Vec::with_capacity(16),
        }
    }
    pub(super) fn own(&mut self, destroy: impl FnOnce() + 'static) {
        self.actions.push(Box::new(destroy));
    }
    pub(super) fn committed(mut self) {
        self.actions.clear();
    }
}
impl Drop for ConstructionRollback {
    fn drop(&mut self) {
        let mut first = None;
        while let Some(action) = self.actions.pop() {
            if let Err(error) = std::panic::catch_unwind(std::panic::AssertUnwindSafe(action)) {
                if first.is_none() {
                    first = Some(error);
                }
            }
        }
        if let Some(error) = first {
            if !std::thread::panicking() {
                std::panic::resume_unwind(error);
            }
        }
    }
}
#[cfg(test)]
mod rollback_tests {
    use super::*;
    #[test]
    fn every_partial_allocation_prefix_rolls_back_same_handles_in_reverse() {
        for prefix in 0..12 {
            let closed = Arc::new(Mutex::new(Vec::new()));
            {
                let mut g = ConstructionRollback::new();
                for n in 0..prefix {
                    let log = closed.clone();
                    g.own(move || log.lock().unwrap().push(n));
                }
            }
            assert_eq!(
                *closed.lock().unwrap(),
                (0..prefix).rev().collect::<Vec<_>>()
            );
        }
    }
    #[test]
    fn committed_handles_transfer_without_destruction() {
        let closed = Arc::new(AtomicBool::new(false));
        {
            let mut g = ConstructionRollback::new();
            let c = closed.clone();
            g.own(move || c.store(true, Ordering::Release));
            g.committed();
        }
        assert!(!closed.load(Ordering::Acquire));
    }
}

// Actual JNI entry custody: acquired BEFORE obtaining a Surface window. Closing
// blocks future entries but cannot erase a call already holding a window.
pub(super) struct PrepareCalls {
    state: Mutex<CallState>,
    stop: Arc<AtomicBool>,
}
struct CallState {
    closed: bool,
    active: usize,
    integrity: bool,
}
pub(crate) struct PrepareCall {
    owner: Arc<PrepareCalls>,
}
impl PrepareCalls {
    pub(super) fn new(stop: Arc<AtomicBool>) -> Self {
        Self {
            state: Mutex::new(CallState {
                closed: false,
                active: 0,
                integrity: true,
            }),
            stop,
        }
    }
    pub(super) fn enter(self: &Arc<Self>) -> Result<PrepareCall, String> {
        let mut s = self
            .state
            .lock()
            .map_err(|_| "renderer call custody poisoned")?;
        if s.closed || !s.integrity || self.stop.load(Ordering::Acquire) {
            return Err("renderer JNI entry closed by actual Stop".into());
        }
        s.active = s
            .active
            .checked_add(1)
            .ok_or("renderer JNI entry count overflow")?;
        Ok(PrepareCall {
            owner: self.clone(),
        })
    }
    pub(super) fn owns(self: &Arc<Self>, call: &PrepareCall) -> bool {
        Arc::ptr_eq(self, &call.owner)
    }
    pub(super) fn close(&self) {
        self.stop.store(true, Ordering::Release);
        self.state.lock().unwrap_or_else(|e| e.into_inner()).closed = true;
    }
    pub(super) fn joined(&self) -> bool {
        self.state
            .lock()
            .map(|s| s.integrity && s.active == 0)
            .unwrap_or(false)
    }
}
impl Drop for PrepareCall {
    fn drop(&mut self) {
        let mut s = self.owner.state.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(n) = s.active.checked_sub(1) {
            s.active = n;
        } else {
            s.integrity = false;
        }
    }
}
// Keep acquired history after successful release; a failed release is retryable
// and can never become a terminal marker merely because metadata was released.
#[derive(Clone, Copy, Default)]
pub(super) struct SourceHold {
    pub(super) acquired: bool,
    pub(super) released: bool,
}
impl SourceHold {
    pub(super) fn settled(&self) -> bool {
        !self.acquired || self.released
    }
    pub(super) fn release(
        &mut self,
        release: impl FnOnce() -> Result<(), String>,
    ) -> Result<(), String> {
        if self.acquired && !self.released {
            release()?;
            self.released = true;
        }
        Ok(())
    }
}
#[derive(Debug, Eq, PartialEq)]
pub(super) enum ReusePhase {
    Ready,
    Committing,
    Committed,
}
pub(super) fn own_reuse_phase(
    committed: bool,
    predecessor: bool,
    peer: bool,
) -> Result<ReusePhase, String> {
    if peer {
        return Ok(if committed {
            ReusePhase::Committed
        } else {
            ReusePhase::Committing
        });
    }
    if predecessor && !committed {
        return Ok(ReusePhase::Ready);
    }
    Err("actual prepared Own reuse route/decoder changed".into())
}
#[cfg(test)]
mod custody_tests {
    use super::*;
    #[test]
    fn entered_before_stop_retains_call_until_real_drop() {
        let c = Arc::new(PrepareCalls::new(Arc::new(AtomicBool::new(false))));
        let call = c.enter().unwrap();
        c.close();
        assert!(!c.joined());
        assert!(c.enter().is_err());
        drop(call);
        assert!(c.joined());
    }
    #[test]
    fn stop_before_entry_has_no_window_permission() {
        let c = Arc::new(PrepareCalls::new(Arc::new(AtomicBool::new(false))));
        c.close();
        assert!(c.enter().is_err());
        assert!(c.joined());
    }
    #[test]
    fn shared_native_stop_refuses_entry() {
        let stop = Arc::new(AtomicBool::new(false));
        let c = Arc::new(PrepareCalls::new(stop.clone()));
        stop.store(true, Ordering::Release);
        assert!(c.enter().is_err());
    }
    #[test]
    fn failed_source_release_is_pending_until_exact_retry() {
        let mut h = SourceHold {
            acquired: true,
            released: false,
        };
        assert!(h
            .release(|| Err("modeled actual source lock fault".into()))
            .is_err());
        assert!(!h.settled());
        assert!(!h.released);
        h.release(|| Ok(())).unwrap();
        assert!(h.released);
        h.release(|| panic!("must not issue second release"))
            .unwrap();
    }
    #[test]
    fn failed_begin_has_no_acquired_hold_claim() {
        let mut h = SourceHold::default();
        h.release(|| panic!("no actual hold was acquired")).unwrap();
        assert!(h.settled());
        assert!(!h.acquired && !h.released);
    }
    #[test]
    fn own_route_commit_changes_counter_disposition() {
        assert_eq!(
            own_reuse_phase(false, true, false).unwrap(),
            ReusePhase::Ready
        );
        assert_eq!(
            own_reuse_phase(false, false, true).unwrap(),
            ReusePhase::Committing
        );
        assert_eq!(
            own_reuse_phase(true, false, true).unwrap(),
            ReusePhase::Committed
        );
        assert!(own_reuse_phase(true, true, false).is_err());
    }
    #[test]
    fn own_wrong_route_or_decoder_refuses() {
        assert!(own_reuse_phase(false, false, false).is_err());
        assert!(own_reuse_phase(true, false, false).is_err());
    }
}
