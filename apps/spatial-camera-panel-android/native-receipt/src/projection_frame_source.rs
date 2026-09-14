use std::{
    collections::{BTreeMap, VecDeque},
    sync::{Arc, Condvar, Mutex},
};

pub(crate) const MAX_PENDING_FRAME_IDENTITIES: usize = 128;
pub(crate) const MAX_BUFFER_REMOVALS: usize = 64;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum ProjectionDecoderRole {
    CompositorVideo,
    ProjectionPeer,
}

pub(crate) const PROJECTION_PEER_STOP_FAILED: i32 = 0;
pub(crate) const PROJECTION_PEER_STOP_CONFIRMED: i32 = 1;
pub(crate) const PROJECTION_PEER_STOP_ALREADY_RETIRED: i32 = 2;

pub(crate) fn projection_peer_stop_result(active_match: bool, retired_match: bool) -> i32 {
    if active_match {
        PROJECTION_PEER_STOP_CONFIRMED
    } else if retired_match {
        PROJECTION_PEER_STOP_ALREADY_RETIRED
    } else {
        PROJECTION_PEER_STOP_FAILED
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct PackedFrameIdentity {
    pub(crate) receiver_generation: u64,
    pub(crate) connection_generation: u64,
    pub(crate) source_elapsed_ns: i64,
    pub(crate) source_unix_ns: i64,
    pub(crate) pair_id: u64,
    pub(crate) left_source_frame: u64,
    pub(crate) right_source_frame: u64,
    pub(crate) left_sensor_timestamp_ns: i64,
    pub(crate) right_sensor_timestamp_ns: i64,
    pub(crate) pair_delta_ns: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct PendingFrameIdentity {
    release_ordinal: u64,
    packed: PackedFrameIdentity,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum FrameIdentityError {
    InvalidTimestamp,
    InvalidMetadata,
    DuplicateTimestamp,
    PendingLimitReached,
    MissingExactTimestamp,
}

#[derive(Debug)]
pub(crate) struct ProjectionFrameSourceState {
    reader_generation: u64,
    decoder_token: u64,
    role: ProjectionDecoderRole,
    route_generation: u64,
    packed_identity_required: bool,
    next_release_ordinal: u64,
    pending: BTreeMap<i64, PendingFrameIdentity>,
    buffer_removals: VecDeque<u64>,
    buffer_reuse_disabled: bool,
}

impl ProjectionFrameSourceState {
    pub(crate) fn new(
        reader_generation: u64,
        decoder_token: u64,
        packed_identity_required: bool,
    ) -> Self {
        Self::new_bound(
            reader_generation,
            decoder_token,
            ProjectionDecoderRole::CompositorVideo,
            0,
            packed_identity_required,
        )
    }

    pub(crate) fn new_bound(
        reader_generation: u64,
        decoder_token: u64,
        role: ProjectionDecoderRole,
        route_generation: u64,
        packed_identity_required: bool,
    ) -> Self {
        Self {
            reader_generation,
            decoder_token,
            role,
            route_generation,
            packed_identity_required,
            next_release_ordinal: 1,
            pending: BTreeMap::new(),
            buffer_removals: VecDeque::new(),
            buffer_reuse_disabled: false,
        }
    }

    pub(crate) fn reader_generation(&self) -> u64 {
        self.reader_generation
    }

    pub(crate) fn decoder_token(&self) -> u64 {
        self.decoder_token
    }

    pub(crate) fn role(&self) -> ProjectionDecoderRole {
        self.role
    }

    pub(crate) fn route_generation(&self) -> u64 {
        self.route_generation
    }

    pub(crate) fn register_packed(
        &mut self,
        output_timestamp_ns: i64,
        packed: PackedFrameIdentity,
    ) -> Result<(), FrameIdentityError> {
        if output_timestamp_ns < 0 {
            return Err(FrameIdentityError::InvalidTimestamp);
        }
        if !valid_packed_identity(packed) {
            return Err(FrameIdentityError::InvalidMetadata);
        }
        if self.pending.contains_key(&output_timestamp_ns) {
            return Err(FrameIdentityError::DuplicateTimestamp);
        }
        if self.pending.len() >= MAX_PENDING_FRAME_IDENTITIES {
            return Err(FrameIdentityError::PendingLimitReached);
        }
        let release_ordinal = self.next_release_ordinal;
        self.next_release_ordinal = self.next_release_ordinal.saturating_add(1);
        self.pending.insert(
            output_timestamp_ns,
            PendingFrameIdentity {
                release_ordinal,
                packed,
            },
        );
        Ok(())
    }

    pub(crate) fn consume_exact(
        &mut self,
        output_timestamp_ns: i64,
    ) -> Result<Option<PackedFrameIdentity>, FrameIdentityError> {
        if output_timestamp_ns < 0 {
            return Err(FrameIdentityError::InvalidTimestamp);
        }
        let Some(accepted) = self.pending.remove(&output_timestamp_ns) else {
            return if self.packed_identity_required {
                Err(FrameIdentityError::MissingExactTimestamp)
            } else {
                Ok(None)
            };
        };

        // acquireLatestImage may discard previously released outputs. Retire only identities
        // whose release ordinal proves that they preceded the exact image just acquired. The
        // timestamp ordering is deliberately irrelevant here.
        self.pending
            .retain(|_, pending| pending.release_ordinal > accepted.release_ordinal);
        Ok(Some(accepted.packed))
    }

    pub(crate) fn discard_exact(&mut self, output_timestamp_ns: i64) {
        self.pending.remove(&output_timestamp_ns);
    }

    pub(crate) fn retire_receiver_connection(
        &mut self,
        receiver_generation: u64,
        connection_generation: u64,
    ) {
        self.pending.retain(|_, pending| {
            pending.packed.receiver_generation != receiver_generation
                || pending.packed.connection_generation != connection_generation
        });
    }

    pub(crate) fn retire_receiver_generation(&mut self, receiver_generation: u64) {
        self.pending
            .retain(|_, pending| pending.packed.receiver_generation != receiver_generation);
    }

    pub(crate) fn record_buffer_removed(&mut self, stable_buffer_id: Option<u64>) {
        let Some(stable_buffer_id) = stable_buffer_id.filter(|value| *value != 0) else {
            self.buffer_reuse_disabled = true;
            self.buffer_removals.clear();
            return;
        };
        if self.buffer_reuse_disabled || self.buffer_removals.contains(&stable_buffer_id) {
            return;
        }
        if self.buffer_removals.len() >= MAX_BUFFER_REMOVALS {
            self.buffer_reuse_disabled = true;
            self.buffer_removals.clear();
            return;
        }
        self.buffer_removals.push_back(stable_buffer_id);
    }

    /// Returns the complete removal tombstone set for this reader generation.
    ///
    /// Tombstones are intentionally not drained: an FPS-dropped/error frame or a latest-slot
    /// replacement must not acknowledge delivery on behalf of the renderer.
    pub(crate) fn buffer_removal_snapshot(&self) -> (Vec<u64>, bool) {
        (
            self.buffer_removals.iter().copied().collect(),
            self.buffer_reuse_disabled,
        )
    }

    pub(crate) fn buffer_reuse_disabled(&self) -> bool {
        self.buffer_reuse_disabled
    }
}

fn valid_packed_identity(identity: PackedFrameIdentity) -> bool {
    (identity.receiver_generation == 0) == (identity.connection_generation == 0)
        && (identity.source_elapsed_ns == 0) == (identity.source_unix_ns == 0)
        && identity.source_elapsed_ns >= 0
        && identity.source_unix_ns >= 0
        && identity.pair_id != 0
        && identity.left_source_frame != 0
        && identity.right_source_frame != 0
        && identity.left_sensor_timestamp_ns > 0
        && identity.right_sensor_timestamp_ns > 0
        && identity.pair_delta_ns
            == identity
                .left_sensor_timestamp_ns
                .abs_diff(identity.right_sensor_timestamp_ns)
}

#[derive(Debug)]
struct CallbackGateState {
    accepting: bool,
    in_flight: usize,
}

#[derive(Debug)]
pub(crate) struct CallbackGate {
    state: Mutex<CallbackGateState>,
    quiescent: Condvar,
}

impl CallbackGate {
    pub(crate) fn new() -> Arc<Self> {
        Arc::new(Self {
            state: Mutex::new(CallbackGateState {
                accepting: true,
                in_flight: 0,
            }),
            quiescent: Condvar::new(),
        })
    }

    pub(crate) fn try_enter(self: &Arc<Self>) -> Option<CallbackPermit> {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if !state.accepting {
            return None;
        }
        state.in_flight = state.in_flight.checked_add(1)?;
        Some(CallbackPermit {
            gate: Arc::clone(self),
        })
    }

    pub(crate) fn deactivate(&self) {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        state.accepting = false;
        if state.in_flight == 0 {
            self.quiescent.notify_all();
        }
    }

    pub(crate) fn wait_for_quiescence(&self) {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        while state.in_flight != 0 {
            match self.quiescent.wait(state) {
                Ok(next) => state = next,
                Err(poisoned) => state = poisoned.into_inner(),
            }
        }
    }
}

#[derive(Debug)]
pub(crate) struct CallbackPermit {
    gate: Arc<CallbackGate>,
}

impl Drop for CallbackPermit {
    fn drop(&mut self) {
        let mut state = self
            .gate
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        state.in_flight = state.in_flight.saturating_sub(1);
        if state.in_flight == 0 {
            self.gate.quiescent.notify_all();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{sync::mpsc, thread, time::Duration};

    fn packed(pair_id: u64) -> PackedFrameIdentity {
        PackedFrameIdentity {
            receiver_generation: 0,
            connection_generation: 0,
            source_elapsed_ns: 0,
            source_unix_ns: 0,
            pair_id,
            left_source_frame: pair_id * 2,
            right_source_frame: pair_id * 2 + 1,
            left_sensor_timestamp_ns: 10_000 + pair_id as i64,
            right_sensor_timestamp_ns: 10_002 + pair_id as i64,
            pair_delta_ns: 2,
        }
    }

    fn embedded_packed(
        receiver_generation: u64,
        connection_generation: u64,
        pair_id: u64,
    ) -> PackedFrameIdentity {
        PackedFrameIdentity {
            receiver_generation,
            connection_generation,
            source_elapsed_ns: 100,
            source_unix_ns: 200,
            ..packed(pair_id)
        }
    }

    #[test]
    fn consumes_only_the_exact_timestamp_and_never_a_neighbour() {
        let mut source = ProjectionFrameSourceState::new(41, 9, true);
        source.register_packed(30, packed(1)).unwrap();
        source.register_packed(10, packed(2)).unwrap();
        source.register_packed(20, packed(3)).unwrap();

        assert_eq!(source.consume_exact(20).unwrap(), Some(packed(3)));
        assert_eq!(
            source.consume_exact(19),
            Err(FrameIdentityError::MissingExactTimestamp)
        );
    }

    #[test]
    fn retires_discarded_outputs_by_release_ordinal_not_timestamp_order() {
        let mut source = ProjectionFrameSourceState::new(41, 9, true);
        source.register_packed(30, packed(1)).unwrap();
        source.register_packed(10, packed(2)).unwrap();
        source.register_packed(20, packed(3)).unwrap();

        assert_eq!(source.consume_exact(10).unwrap(), Some(packed(2)));
        assert_eq!(
            source.consume_exact(30),
            Err(FrameIdentityError::MissingExactTimestamp)
        );
        assert_eq!(source.consume_exact(20).unwrap(), Some(packed(3)));
    }

    #[test]
    fn duplicate_missing_and_overflow_fail_closed() {
        let mut source = ProjectionFrameSourceState::new(41, 9, true);
        source.register_packed(1, packed(1)).unwrap();
        assert_eq!(
            source.register_packed(1, packed(2)),
            Err(FrameIdentityError::DuplicateTimestamp)
        );
        for index in 2..=MAX_PENDING_FRAME_IDENTITIES as i64 {
            source.register_packed(index, packed(index as u64)).unwrap();
        }
        assert_eq!(
            source.register_packed(10_000, packed(10_000)),
            Err(FrameIdentityError::PendingLimitReached)
        );
        assert_eq!(
            source.consume_exact(20_000),
            Err(FrameIdentityError::MissingExactTimestamp)
        );
    }

    #[test]
    fn connection_and_generation_retirement_clear_only_matching_exact_pts() {
        let mut source = ProjectionFrameSourceState::new_bound(
            41,
            9,
            ProjectionDecoderRole::ProjectionPeer,
            7,
            true,
        );
        source
            .register_packed(1, embedded_packed(5, 10, 1))
            .unwrap();
        source
            .register_packed(2, embedded_packed(5, 11, 2))
            .unwrap();
        source
            .register_packed(3, embedded_packed(6, 10, 3))
            .unwrap();

        source.retire_receiver_connection(5, 10);
        assert_eq!(
            source.consume_exact(1),
            Err(FrameIdentityError::MissingExactTimestamp)
        );
        assert_eq!(
            source.consume_exact(2).unwrap(),
            Some(embedded_packed(5, 11, 2))
        );

        source.retire_receiver_generation(6);
        assert_eq!(
            source.consume_exact(3),
            Err(FrameIdentityError::MissingExactTimestamp)
        );
    }

    #[test]
    fn reader_generation_and_decoder_token_are_independent_identities() {
        let source = ProjectionFrameSourceState::new(41, 9, false);
        assert_eq!(source.reader_generation(), 41);
        assert_eq!(source.decoder_token(), 9);
        assert_ne!(source.reader_generation(), source.decoder_token());
    }

    #[test]
    fn same_pts_is_isolated_across_decoder_roles_and_route_generations() {
        let mut compositor = ProjectionFrameSourceState::new_bound(
            41,
            9,
            ProjectionDecoderRole::CompositorVideo,
            0,
            true,
        );
        let mut peer = ProjectionFrameSourceState::new_bound(
            42,
            10,
            ProjectionDecoderRole::ProjectionPeer,
            7,
            true,
        );
        compositor.register_packed(33, packed(1)).unwrap();
        peer.register_packed(33, packed(2)).unwrap();

        assert_eq!(compositor.consume_exact(33).unwrap(), Some(packed(1)));
        assert_eq!(peer.consume_exact(33).unwrap(), Some(packed(2)));
        assert_eq!(compositor.role(), ProjectionDecoderRole::CompositorVideo);
        assert_eq!(peer.role(), ProjectionDecoderRole::ProjectionPeer);
        assert_eq!(peer.route_generation(), 7);
    }

    #[test]
    fn removal_before_fps_drop_remains_in_the_next_publishable_snapshot() {
        let mut source = ProjectionFrameSourceState::new(41, 9, false);
        source.record_buffer_removed(Some(7));

        // An FPS-dropped frame does not request or mutate removal delivery.
        assert_eq!(source.buffer_removal_snapshot(), (vec![7], false));
    }

    #[test]
    fn removal_before_descriptor_error_remains_in_the_next_publishable_snapshot() {
        let mut source = ProjectionFrameSourceState::new(41, 9, false);
        source.record_buffer_removed(Some(8));

        // A descriptor/AHB error likewise publishes nothing and acknowledges nothing.
        assert_eq!(source.buffer_removal_snapshot(), (vec![8], false));
    }

    #[test]
    fn latest_frame_replacement_repeats_unacknowledged_tombstones() {
        let mut source = ProjectionFrameSourceState::new(41, 9, false);
        source.record_buffer_removed(Some(7));
        assert_eq!(source.buffer_removal_snapshot(), (vec![7], false));

        // A later latest-slot frame carries the cumulative set even if the first was never read.
        source.record_buffer_removed(Some(8));
        assert_eq!(source.buffer_removal_snapshot(), (vec![7, 8], false));
        assert_eq!(source.buffer_removal_snapshot(), (vec![7, 8], false));
    }

    #[test]
    fn removal_tombstones_are_isolated_by_reader_generation() {
        let mut old = ProjectionFrameSourceState::new(41, 9, false);
        let new = ProjectionFrameSourceState::new(42, 10, false);
        old.record_buffer_removed(Some(7));

        assert_eq!(old.buffer_removal_snapshot(), (vec![7], false));
        assert_eq!(new.buffer_removal_snapshot(), (Vec::new(), false));
    }

    #[test]
    fn buffer_removal_overflow_permanently_disables_reuse_for_the_generation() {
        let mut source = ProjectionFrameSourceState::new(41, 9, false);
        for id in 1..=MAX_BUFFER_REMOVALS as u64 {
            source.record_buffer_removed(Some(id));
        }
        source.record_buffer_removed(Some(MAX_BUFFER_REMOVALS as u64 + 1));
        assert_eq!(source.buffer_removal_snapshot(), (Vec::new(), true));
        source.record_buffer_removed(Some(1));
        assert_eq!(source.buffer_removal_snapshot(), (Vec::new(), true));

        let mut unknown = ProjectionFrameSourceState::new(42, 10, false);
        unknown.record_buffer_removed(None);
        assert_eq!(unknown.buffer_removal_snapshot(), (Vec::new(), true));
    }

    #[test]
    fn deactivation_waits_for_image_and_buffer_callback_permits() {
        let gate = CallbackGate::new();
        let image = gate.try_enter().unwrap();
        let buffer = gate.try_enter().unwrap();
        gate.deactivate();
        assert!(gate.try_enter().is_none());

        let (done_tx, done_rx) = mpsc::channel();
        let waiter = Arc::clone(&gate);
        let thread = thread::spawn(move || {
            waiter.wait_for_quiescence();
            done_tx.send(()).unwrap();
        });
        assert!(done_rx.recv_timeout(Duration::from_millis(20)).is_err());
        drop(image);
        assert!(done_rx.recv_timeout(Duration::from_millis(20)).is_err());
        drop(buffer);
        done_rx.recv_timeout(Duration::from_secs(1)).unwrap();
        thread.join().unwrap();
    }

    #[test]
    fn peer_stop_distinguishes_confirmation_idempotence_and_rejection() {
        assert_eq!(
            projection_peer_stop_result(true, false),
            PROJECTION_PEER_STOP_CONFIRMED
        );
        assert_eq!(
            projection_peer_stop_result(false, true),
            PROJECTION_PEER_STOP_ALREADY_RETIRED
        );
        assert_eq!(
            projection_peer_stop_result(false, false),
            PROJECTION_PEER_STOP_FAILED
        );
    }
}
