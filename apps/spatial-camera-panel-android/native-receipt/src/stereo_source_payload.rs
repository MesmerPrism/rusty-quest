//! Source-slot payload pins exact contents independently from allocation import caches.
use crate::stereo_input_set::*;
use std::sync::Mutex;
#[derive(Clone)]
pub(crate) enum StereoSourceLease<Own, Peer> {
    Own(Own),
    Peer(RetainedPeerPayload<Peer>),
}
pub(crate) struct OwnPeerSourceSet<Own, Peer> {
    inputs: Mutex<StereoInputSet<StereoSourceLease<Own, Peer>>>,
    peer_payloads: std::sync::Arc<PeerPayloadCounter>,
}
impl<O, P> Default for OwnPeerSourceSet<O, P> {
    fn default() -> Self {
        Self {
            inputs: Mutex::new(StereoInputSet::default()),
            peer_payloads: std::sync::Arc::new(PeerPayloadCounter::default()),
        }
    }
}
impl<O: Clone, P: Clone> OwnPeerSourceSet<O, P> {
    pub(crate) fn peer_retirement_facts(&self) -> Result<PeerPayloadRetirementFacts, String> {
        use std::sync::atomic::Ordering;
        let inputs = self
            .inputs
            .lock()
            .map_err(|_| "Peer source owner poisoned")?;
        if self.peer_payloads.integrity_failed.load(Ordering::Acquire) {
            return Err("Peer payload custody integrity failed".into());
        }
        Ok(PeerPayloadRetirementFacts {
            inactive: inputs.retired_or_unbound(StereoOrigin::PeerStereo),
            retained: self.peer_payloads.retained.load(Ordering::Acquire),
        })
    }
    pub(crate) fn bind(&self, origin: StereoOrigin, epoch: SourceEpoch) -> Result<(), String> {
        self.inputs
            .lock()
            .map_err(|_| "source set poisoned")?
            .bind(origin, epoch)
            .map_err(|e| format!("source bind {e:?}"))
    }
    pub(crate) fn retire(&self, origin: StereoOrigin, epoch: SourceEpoch) -> Result<(), String> {
        if self
            .inputs
            .lock()
            .map_err(|_| "source set poisoned")?
            .retire(origin, epoch)
        {
            Ok(())
        } else {
            Err("stale source retirement".into())
        }
    }
    pub(crate) fn retire_stopped_or_unbound(
        &self,
        origin: StereoOrigin,
        epoch: SourceEpoch,
    ) -> Result<Option<bool>, String> {
        Ok(self
            .inputs
            .lock()
            .map_err(|_| "source set poisoned")?
            .retire_stopped_or_unbound(origin, epoch))
    }
    pub(crate) fn publish_own(&self, frame: RetainedStereoFrame<O>) -> Result<(), String> {
        self.publish(
            StereoOrigin::OwnStereo,
            RetainedStereoFrame {
                identity: frame.identity,
                observed_at_ns: frame.observed_at_ns,
                lease: StereoSourceLease::Own(frame.lease),
            },
        )
    }
    // Peer owner must pass its real ImageReader frame lease after exact reader/token checks.
    pub(crate) fn publish_peer(&self, frame: RetainedStereoFrame<P>) -> Result<(), String> {
        self.publish(
            StereoOrigin::PeerStereo,
            RetainedStereoFrame {
                identity: frame.identity,
                observed_at_ns: frame.observed_at_ns,
                lease: StereoSourceLease::Peer(RetainedPeerPayload::new(
                    frame.lease,
                    &self.peer_payloads,
                )?),
            },
        )
    }
    fn publish(
        &self,
        origin: StereoOrigin,
        frame: RetainedStereoFrame<StereoSourceLease<O, P>>,
    ) -> Result<(), String> {
        self.inputs
            .lock()
            .map_err(|_| "source set poisoned")?
            .publish(origin, frame)
            .map_err(|e| format!("source publication {e:?}"))
    }
    // Sample the native clock while holding the source-set lock. A producer cannot
    // publish a newer timestamp between the clock sample and this immutable view.
    // Observe actual Own freshness and preserve its first failed predicate.
    pub(crate) fn own_current(
        &self,
        now: fn() -> Option<u64>,
        max_age: u64,
    ) -> Result<RetainedStereoFrame<StereoSourceLease<O, P>>, &'static str> {
        let inputs = self
            .inputs
            .lock()
            .map_err(|_| "concurrent Own native admission SOURCE_STATE")?;
        let now = now().ok_or("concurrent Own native admission CLOCK")?;
        let frame = inputs
            .fresh_observed(StereoOrigin::OwnStereo, now, max_age)
            .map_err(|reason| match reason {
                FreshnessFailure::Absent => "concurrent Own native admission FRAME_ABSENT",
                FreshnessFailure::Future => "concurrent Own native admission FRAME_FUTURE",
                FreshnessFailure::Stale => "concurrent Own native admission FRAME_STALE",
            })?;
        Ok(RetainedStereoFrame {
            identity: frame.identity,
            observed_at_ns: frame.observed_at_ns,
            lease: frame.lease.clone(),
        })
    }

    pub(crate) fn snapshot(
        &self,
        now: u64,
        max_age: u64,
    ) -> Result<[Option<RetainedStereoFrame<StereoSourceLease<O, P>>>; 2], String> {
        let inputs = self.inputs.lock().map_err(|_| "source set poisoned")?;
        let snapshot = inputs.fresh_inputs(now, max_age);
        let clone = |f: Option<&RetainedStereoFrame<StereoSourceLease<O, P>>>| {
            f.map(|f| RetainedStereoFrame {
                identity: f.identity,
                observed_at_ns: f.observed_at_ns,
                lease: f.lease.clone(),
            })
        };
        Ok([clone(snapshot.own), clone(snapshot.peer)])
    }
}
// Called on capture actor by accepted native host setup, never by JNI metadata.
#[cfg(target_os = "android")]
pub(crate) fn install_own_capture_adapter<P: Clone + 'static>(
    sources: std::sync::Arc<OwnPeerSourceSet<crate::own_packed_pool::PackedLease, P>>,
    limits: crate::own_packed_pool_policy::HoldLimits,
) -> Result<(), String> {
    use crate::own_packed_pool_jni as jni;
    use std::rc::Rc;
    jni::install_capture_owner(limits)?;
    let publisher = sources.clone();
    jni::install_capture_publisher(Rc::new(move |frame| publisher.publish_own(frame)))?;
    let bind = sources.clone();
    jni::install_capture_epoch_callbacks(
        Rc::new(move |epoch| bind.bind(StereoOrigin::OwnStereo, epoch)),
        Rc::new(move |epoch| sources.retire(StereoOrigin::OwnStereo, epoch)),
    )
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    fn frame(lease: Arc<u32>) -> RetainedStereoFrame<Arc<u32>> {
        RetainedStereoFrame {
            identity: StereoFrameIdentity {
                epoch: SourceEpoch {
                    process_generation: 1,
                    source_generation: 1,
                },
                pair_sequence: 1,
                left_timestamp_ns: 2,
                right_timestamp_ns: 3,
                packed_pts_ns: 3,
                calibration_revision: None,
            },
            observed_at_ns: 10,
            lease,
        }
    }
    #[test]
    fn source_retirement_preserves_peer_and_borrowed_content() {
        let set = OwnPeerSourceSet::default();
        let own = Arc::new(10);
        let peer = Arc::new(20);
        let epoch = frame(own.clone()).identity.epoch;
        set.bind(StereoOrigin::OwnStereo, epoch).unwrap();
        set.bind(StereoOrigin::PeerStereo, epoch).unwrap();
        set.publish_own(frame(own.clone())).unwrap();
        set.publish_peer(frame(peer.clone())).unwrap();
        let held = set.snapshot(11, 10).unwrap();
        set.retire(StereoOrigin::OwnStereo, epoch).unwrap();
        assert!(set.snapshot(11, 10).unwrap()[1].is_some());
        assert_eq!(Arc::strong_count(&own), 2);
        drop(held);
        assert_eq!(Arc::strong_count(&own), 1);
    }
}

// The payload root follows the real retained Peer frame through source-slot,
// import/cache/recording/GPU clones. Forgetting physically pending resources
// also retains this root; an observation cannot decrement it.
#[derive(Default)]
struct PeerPayloadCounter {
    retained: std::sync::atomic::AtomicUsize,
    integrity_failed: std::sync::atomic::AtomicBool,
}
struct PeerPayloadRoot<P> {
    payload: std::mem::ManuallyDrop<P>,
    counter: std::sync::Arc<PeerPayloadCounter>,
}
impl<P> Drop for PeerPayloadRoot<P> {
    fn drop(&mut self) {
        use std::sync::atomic::Ordering;
        // Keep custody counted until the actual payload destructor has returned.
        // A destructor panic conservatively leaves the count nonzero.
        unsafe {
            std::mem::ManuallyDrop::drop(&mut self.payload);
        }
        if self
            .counter
            .retained
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| n.checked_sub(1))
            .is_err()
        {
            self.counter.integrity_failed.store(true, Ordering::Release);
        }
    }
}
#[derive(Clone)]
pub(crate) struct RetainedPeerPayload<P> {
    root: std::sync::Arc<PeerPayloadRoot<P>>,
}
impl<P> RetainedPeerPayload<P> {
    fn new(payload: P, counter: &std::sync::Arc<PeerPayloadCounter>) -> Result<Self, String> {
        use std::sync::atomic::Ordering;
        if counter.integrity_failed.load(Ordering::Acquire) {
            return Err("Peer payload custody integrity failed".into());
        }
        counter
            .retained
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| n.checked_add(1))
            .map_err(|_| "Peer payload custody exhausted")?;
        Ok(Self {
            root: std::sync::Arc::new(PeerPayloadRoot {
                payload: std::mem::ManuallyDrop::new(payload),
                counter: counter.clone(),
            }),
        })
    }
}
impl<P> std::ops::Deref for RetainedPeerPayload<P> {
    type Target = P;
    fn deref(&self) -> &P {
        &self.root.payload
    }
}

pub(crate) struct PeerPayloadRetirementFacts {
    pub inactive: bool,
    pub retained: usize,
}

#[cfg(test)]
mod peer_payload_retirement_controls {
    use super::*;
    #[test]
    fn inactive_slot_is_not_retired_while_an_actual_peer_payload_clone_is_held() {
        let set: OwnPeerSourceSet<std::sync::Arc<u64>, std::sync::Arc<u64>> =
            OwnPeerSourceSet::default();
        let epoch = SourceEpoch {
            process_generation: 1,
            source_generation: 1,
        };
        let identity = StereoFrameIdentity {
            epoch,
            pair_sequence: 1,
            left_timestamp_ns: 2,
            right_timestamp_ns: 3,
            packed_pts_ns: 3,
            calibration_revision: None,
        };
        assert!(set.peer_retirement_facts().unwrap().inactive);
        set.bind(StereoOrigin::PeerStereo, epoch).unwrap();
        assert!(
            !set.peer_retirement_facts().unwrap().inactive,
            "bound but never-published is active"
        );
        set.publish_peer(RetainedStereoFrame {
            identity,
            observed_at_ns: 10,
            lease: std::sync::Arc::new(20),
        })
        .unwrap();
        let held = set.snapshot(11, 10).unwrap();
        set.retire(StereoOrigin::PeerStereo, epoch).unwrap();
        let state = set.peer_retirement_facts().unwrap();
        assert!(state.inactive);
        assert_eq!(state.retained, 1);
        assert!(
            set.bind(StereoOrigin::PeerStereo, epoch).is_err(),
            "retired epoch cannot reactivate"
        );
        drop(held);
        assert_eq!(set.peer_retirement_facts().unwrap().retained, 0);
    }
    #[test]
    fn pending_forgotten_payload_cannot_be_claimed_retired_by_a_snapshot() {
        let set: OwnPeerSourceSet<u64, u64> = OwnPeerSourceSet::default();
        let payload = RetainedPeerPayload::new(5, &set.peer_payloads).unwrap();
        let held = payload.clone();
        drop(payload);
        std::mem::forget(held);
        assert_eq!(set.peer_retirement_facts().unwrap().retained, 1);
    }
}
