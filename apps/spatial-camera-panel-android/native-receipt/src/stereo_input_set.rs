// PROSPECTIVE ONLY. Not registered, compiled, or installed in the Quest crate.
// Payload must own the real source image/pool lease, never a bare AHB address.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum StereoOrigin { OwnStereo, PeerStereo }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct SourceEpoch {
    pub process_generation: u64,
    pub source_generation: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct StereoFrameIdentity {
    pub epoch: SourceEpoch,
    pub pair_sequence: u64,
    pub left_timestamp_ns: i64,
    pub right_timestamp_ns: i64,
    pub packed_pts_ns: i64,
    // Optional reference to separately authenticated calibration. None supports
    // image-only publication; calibrated/reprojected use must require Some.
    pub calibration_revision: Option<u64>,
}

pub(crate) struct RetainedStereoFrame<L> {
    pub identity: StereoFrameIdentity,
    pub lease: L,
    // Local monotonic acquisition time, not remote sensor time or wall time.
    pub observed_at_ns: u64,
}

struct SourceSlot<L> {
    epoch: Option<SourceEpoch>,
    active: bool,
    last_sequence: Option<u64>,
    latest: Option<RetainedStereoFrame<L>>,
}

impl<L> Default for SourceSlot<L> {
    fn default() -> Self { Self { epoch: None, active: false, last_sequence: None, latest: None } }
}

// One immutable view of both independently fresh sources. Consumers clone their
// real retained lease before releasing the input-set lock or submitting GPU work.
pub(crate) struct StereoInputSnapshot<'a, L> {
    pub(crate) own: Option<&'a RetainedStereoFrame<L>>,
    pub(crate) peer: Option<&'a RetainedStereoFrame<L>>,
}

pub(crate) struct StereoInputSet<L> { own: SourceSlot<L>, peer: SourceSlot<L> }

impl<L> Default for StereoInputSet<L> {
    fn default() -> Self { Self { own: SourceSlot::default(), peer: SourceSlot::default() } }
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum PublishError { UnboundEpoch, Replay, InvalidIdentity, RegressedClock }

impl<L> StereoInputSet<L> {
    fn slot(&self, origin: StereoOrigin) -> &SourceSlot<L> {
        match origin { StereoOrigin::OwnStereo => &self.own, StereoOrigin::PeerStereo => &self.peer }
    }
    fn slot_mut(&mut self, origin: StereoOrigin) -> &mut SourceSlot<L> {
        match origin { StereoOrigin::OwnStereo => &mut self.own, StereoOrigin::PeerStereo => &mut self.peer }
    }

    // Invoked only after the source owner accepts a new epoch. Rebinding a
    // source drops its latest reference, not another source or GPU-held leases.
    // Caller must retire old producer callbacks before accepting the new epoch.
    pub(crate) fn bind(&mut self, origin: StereoOrigin, epoch: SourceEpoch) -> Result<(), PublishError> {
        if epoch.process_generation == 0 || epoch.source_generation == 0 {
            return Err(PublishError::InvalidIdentity);
        }
        let slot = self.slot_mut(origin);
        if slot.epoch == Some(epoch) { return if slot.active { Ok(()) } else { Err(PublishError::Replay) }; }
        if slot.epoch.is_some_and(|old| epoch.process_generation < old.process_generation || (old.process_generation == epoch.process_generation
            && epoch.source_generation <= old.source_generation)) {
            return Err(PublishError::Replay);
        }
        *slot = SourceSlot { epoch: Some(epoch), active: true, last_sequence: None, latest: None };
        Ok(())
    }

    pub(crate) fn publish(&mut self, origin: StereoOrigin, frame: RetainedStereoFrame<L>)
        -> Result<(), PublishError> {
        let slot = self.slot_mut(origin);
        if !slot.active || slot.epoch != Some(frame.identity.epoch) { return Err(PublishError::UnboundEpoch); }
        if frame.identity.pair_sequence == 0 || frame.identity.left_timestamp_ns <= 0
            || frame.identity.right_timestamp_ns <= 0 || frame.identity.packed_pts_ns <= 0
            || frame.identity.calibration_revision == Some(0) {
            return Err(PublishError::InvalidIdentity);
        }
        if slot.last_sequence.is_some_and(|last| frame.identity.pair_sequence <= last) {
            return Err(PublishError::Replay);
        }
        if slot.latest.as_ref().is_some_and(|last| frame.observed_at_ns < last.observed_at_ns) {
            return Err(PublishError::RegressedClock);
        }
        slot.last_sequence = Some(frame.identity.pair_sequence);
        slot.latest = Some(frame);
        Ok(())
    }

    // Exact-owner retirement: stale retire callbacks cannot clear a successor.
    pub(crate) fn retire(&mut self, origin: StereoOrigin, epoch: SourceEpoch) -> bool {
        let slot = self.slot_mut(origin);
        if slot.epoch != Some(epoch) { return false; }
        slot.active = false;
        slot.latest = None;
        // Keep replay watermark until a different accepted epoch is bound.
        true
    }

    // A single local monotonic observation point; source timestamps stay identity.
    pub(crate) fn fresh_inputs(&self, now_ns: u64, max_age_ns: u64)
        -> StereoInputSnapshot<'_, L> {
        StereoInputSnapshot {
            own: self.fresh(StereoOrigin::OwnStereo, now_ns, max_age_ns),
            peer: self.fresh(StereoOrigin::PeerStereo, now_ns, max_age_ns),
        }
    }
    pub(crate) fn fresh(&self, origin: StereoOrigin, now_ns: u64, max_age_ns: u64)
        -> Option<&RetainedStereoFrame<L>> {
        let frame = self.slot(origin).latest.as_ref()?;
        let age = now_ns.checked_sub(frame.observed_at_ns)?;
        (age <= max_age_ns).then_some(frame)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const E: SourceEpoch = SourceEpoch { process_generation: 1, source_generation: 1 };
    fn frame(seq: u64, observed: u64) -> RetainedStereoFrame<&'static str> {
        RetainedStereoFrame { identity: StereoFrameIdentity { epoch: E, pair_sequence: seq,
            left_timestamp_ns: 10, right_timestamp_ns: 12, packed_pts_ns: 12,
            calibration_revision: None }, lease: "owned", observed_at_ns: observed }
    }
    #[test] fn origins_coexist_and_peer_retirement_preserves_own() {
        let mut set = StereoInputSet::default();
        set.bind(StereoOrigin::OwnStereo, E).unwrap();
        set.bind(StereoOrigin::PeerStereo, E).unwrap();
        set.publish(StereoOrigin::OwnStereo, frame(1, 100)).unwrap();
        set.publish(StereoOrigin::PeerStereo, frame(1, 100)).unwrap();
        assert!(set.retire(StereoOrigin::PeerStereo, E));
        assert!(set.fresh(StereoOrigin::OwnStereo, 101, 10).is_some());
        assert!(set.fresh(StereoOrigin::PeerStereo, 101, 10).is_none());
    }
    #[test] fn delayed_callback_and_replay_cannot_replace_successor() {
        let mut set = StereoInputSet::default();
        set.bind(StereoOrigin::OwnStereo, E).unwrap();
        set.publish(StereoOrigin::OwnStereo, frame(2, 100)).unwrap();
        assert_eq!(set.publish(StereoOrigin::OwnStereo, frame(1, 101)), Err(PublishError::Replay));
        let next = SourceEpoch { source_generation: 2, ..E };
        set.bind(StereoOrigin::OwnStereo, next).unwrap();
        assert_eq!(set.publish(StereoOrigin::OwnStereo, frame(3, 102)), Err(PublishError::UnboundEpoch));
        assert!(!set.retire(StereoOrigin::OwnStereo, E));
    }
    #[test] fn clock_regression_and_stale_observation_are_unavailable() {
        let mut set = StereoInputSet::default();
        set.bind(StereoOrigin::OwnStereo, E).unwrap();
        set.publish(StereoOrigin::OwnStereo, frame(1, 100)).unwrap();
        assert!(set.fresh(StereoOrigin::OwnStereo, 99, 10).is_none());
        assert!(set.fresh(StereoOrigin::OwnStereo, 111, 10).is_none());
        assert_eq!(set.publish(StereoOrigin::OwnStereo, frame(2, 99)), Err(PublishError::RegressedClock));
    }
    #[test] fn retired_epoch_cannot_be_reopened_or_publish_again() {
        let mut set = StereoInputSet::default();
        set.bind(StereoOrigin::OwnStereo, E).unwrap();
        set.publish(StereoOrigin::OwnStereo, frame(1, 100)).unwrap();
        assert!(set.retire(StereoOrigin::OwnStereo, E));
        assert_eq!(set.bind(StereoOrigin::OwnStereo, E), Err(PublishError::Replay));
        assert_eq!(set.publish(StereoOrigin::OwnStereo, frame(2, 101)), Err(PublishError::UnboundEpoch));
    }
}
