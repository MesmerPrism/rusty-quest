#[path = "../../../../apps/spatial-camera-panel-android/native-receipt/src/own_packed_pool_policy.rs"]
mod pool_policy;
#[path = "../../../../apps/spatial-camera-panel-android/native-receipt/src/stereo_input_set.rs"]
mod source_set;

#[cfg(test)]
mod tests {
    use super::pool_policy::ReadyObservationOrder;
    use super::source_set::{RetainedStereoFrame, SourceEpoch, StereoFrameIdentity, StereoInputSet, StereoOrigin};

    fn frame(pair: u64, observed_at_ns: u64) -> RetainedStereoFrame<u64> {
        RetainedStereoFrame {
            identity: StereoFrameIdentity {
                epoch: SourceEpoch { process_generation: 1, source_generation: 1 },
                pair_sequence: pair,
                left_timestamp_ns: pair as i64,
                right_timestamp_ns: pair as i64,
                packed_pts_ns: pair as i64,
                calibration_revision: None,
            },
            lease: pair,
            observed_at_ns,
        }
    }

    #[test]
    fn later_fence_first_then_older_fence_does_not_fail_actual_source_publisher() {
        let mut source = StereoInputSet::default();
        source.bind(StereoOrigin::OwnStereo, frame(1, 1).identity.epoch).unwrap();
        let mut ready = ReadyObservationOrder::default();
        // Physical slot for 101 remains Pending in the first poll; 102 completes.
        let time = ready.admit(102, 200).unwrap();
        source.publish(StereoOrigin::OwnStereo, frame(102, time)).unwrap();
        // The old fence later completes, but its lease is simply retired as Ready.
        assert_eq!(ready.admit(101, 250), None);
        assert_eq!(source.fresh(StereoOrigin::OwnStereo, 260, 100).unwrap().identity.pair_sequence, 102);
        // A later accepted pair is still published and remains current.
        let time = ready.admit(103, 270).unwrap();
        source.publish(StereoOrigin::OwnStereo, frame(103, time)).unwrap();
        assert_eq!(source.fresh(StereoOrigin::OwnStereo, 280, 100).unwrap().identity.pair_sequence, 103);
    }

    #[test]
    fn reverse_slot_scan_clock_does_not_regress_actual_source_publisher() {
        let mut source = StereoInputSet::default();
        source.bind(StereoOrigin::OwnStereo, frame(1, 1).identity.epoch).unwrap();
        let mut ready = ReadyObservationOrder::default();
        // Frame 202 was sampled first at 300; frame 201 second at 310.
        // The pool takes the actual max from this poll, then publishes in pair order.
        let batch_ns = [300_u64, 310].into_iter().max().unwrap();
        for pair in [201, 202] {
            let ordered = ready.admit(pair, batch_ns).unwrap();
            source.publish(StereoOrigin::OwnStereo, frame(pair, ordered)).unwrap();
        }
        assert_eq!(source.fresh(StereoOrigin::OwnStereo, 320, 100).unwrap().observed_at_ns, 310);
    }

    #[test]
    fn genuine_backward_clock_in_later_poll_is_still_rejected() {
        let mut source = StereoInputSet::default();
        source.bind(StereoOrigin::OwnStereo, frame(1, 1).identity.epoch).unwrap();
        let mut ready = ReadyObservationOrder::default();
        source.publish(StereoOrigin::OwnStereo,
            frame(201, ready.admit(201, 310).unwrap())).unwrap();
        let later_poll = frame(202, ready.admit(202, 300).unwrap());
        assert_eq!(source.publish(StereoOrigin::OwnStereo, later_poll),
            Err(super::source_set::PublishError::RegressedClock));
    }
}
