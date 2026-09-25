use std::collections::BTreeMap;
use std::sync::{LazyLock, Mutex};

const MAX_PENDING_RECEIVER_FRAMES: usize = 128;

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
struct FrameKey {
    receiver_generation: u64,
    connection_generation: u64,
    route_generation: u64,
    decoder_token: u64,
    reader_generation: u64,
    presentation_time_ns: i64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct ReceiverFrameIdentity {
    pub(crate) receiver_generation: u64,
    pub(crate) connection_generation: u64,
    pub(crate) route_generation: u64,
    pub(crate) decoder_token: u64,
    pub(crate) reader_generation: u64,
    pub(crate) presentation_time_ns: i64,
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
pub(crate) enum ReceiverFrameRegistrationResult {
    Accepted,
    Invalid,
    Duplicate,
    CapacityExceeded,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum ReceiverFrameObservationResult {
    Accepted,
    Missing,
    IdentityMismatch,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct ReceiverFrameObservation {
    pub(crate) identity: ReceiverFrameIdentity,
    pub(crate) registered_monotonic_ns: u64,
    pub(crate) rendered_monotonic_ns: u64,
    pub(crate) acquired_monotonic_ns: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct TimedReceiverFrameObservation {
    pub(crate) observation: ReceiverFrameObservation,
    pub(crate) observed_at_monotonic_ns: u64,
    pub(crate) witness_age_ns: u64,
}

impl ReceiverFrameObservation {
    pub(crate) fn is_fresh_at(&self, now_monotonic_ns: u64, max_age_ns: u64) -> bool {
        if max_age_ns == 0 {
            return false;
        }
        let oldest_witness = self
            .registered_monotonic_ns
            .min(self.rendered_monotonic_ns)
            .min(self.acquired_monotonic_ns);
        oldest_witness <= now_monotonic_ns && now_monotonic_ns - oldest_witness <= max_age_ns
    }

    /// Keeps the native observation instant and oldest-witness age together.
    /// All words must be representable by the signed JNI long array.
    pub(crate) fn timed_at(
        self,
        observed_at_monotonic_ns: u64,
        max_age_ns: u64,
    ) -> Option<TimedReceiverFrameObservation> {
        let registered = self.registered_monotonic_ns;
        let rendered = self.rendered_monotonic_ns;
        let acquired = self.acquired_monotonic_ns;
        if registered == 0
            || registered > rendered
            || registered > acquired
            || rendered > observed_at_monotonic_ns
            || acquired > observed_at_monotonic_ns
            || observed_at_monotonic_ns > i64::MAX as u64
            || max_age_ns == 0
        {
            return None;
        }
        let oldest = registered.min(rendered).min(acquired);
        let age = observed_at_monotonic_ns.checked_sub(oldest)?;
        (age <= max_age_ns).then_some(TimedReceiverFrameObservation {
            observation: self,
            observed_at_monotonic_ns,
            witness_age_ns: age,
        })
    }
}

#[derive(Clone, Copy)]
struct PendingObservation {
    identity: ReceiverFrameIdentity,
    registered_monotonic_ns: u64,
    rendered_monotonic_ns: u64,
    acquired_monotonic_ns: u64,
}

#[derive(Default)]
struct ObservationState {
    pending: BTreeMap<FrameKey, PendingObservation>,
    latest_complete: Option<ReceiverFrameObservation>,
}

static OBSERVATIONS: LazyLock<Mutex<ObservationState>> =
    LazyLock::new(|| Mutex::new(ObservationState::default()));

pub(crate) fn register_receiver_frame(
    identity: ReceiverFrameIdentity,
    now_monotonic_ns: u64,
) -> ReceiverFrameRegistrationResult {
    if !valid_identity(identity) || now_monotonic_ns == 0 {
        return ReceiverFrameRegistrationResult::Invalid;
    }
    let key = key(identity);
    let Ok(mut state) = OBSERVATIONS.lock() else {
        return ReceiverFrameRegistrationResult::Invalid;
    };
    if state.pending.contains_key(&key) {
        return ReceiverFrameRegistrationResult::Duplicate;
    }
    if state.pending.len() >= MAX_PENDING_RECEIVER_FRAMES {
        return ReceiverFrameRegistrationResult::CapacityExceeded;
    }
    state.pending.insert(
        key,
        PendingObservation {
            identity,
            registered_monotonic_ns: now_monotonic_ns,
            rendered_monotonic_ns: 0,
            acquired_monotonic_ns: 0,
        },
    );
    ReceiverFrameRegistrationResult::Accepted
}

pub(crate) fn record_receiver_frame_rendered(
    identity: ReceiverFrameIdentity,
    now_monotonic_ns: u64,
) -> ReceiverFrameObservationResult {
    observe(identity, now_monotonic_ns, true)
}

pub(crate) fn record_receiver_frame_acquired(
    identity: ReceiverFrameIdentity,
    now_monotonic_ns: u64,
) -> ReceiverFrameObservationResult {
    observe(identity, now_monotonic_ns, false)
}

pub(crate) fn latest_receiver_frame_observation(
    receiver_generation: u64,
    connection_generation: u64,
    route_generation: u64,
    decoder_token: u64,
    reader_generation: u64,
    now_monotonic_ns: u64,
) -> Option<ReceiverFrameObservation> {
    let state = OBSERVATIONS.lock().ok()?;
    let observation = state.latest_complete?;
    let identity = observation.identity;
    (identity.receiver_generation == receiver_generation
        && identity.connection_generation == connection_generation
        && identity.route_generation == route_generation
        && identity.decoder_token == decoder_token
        && identity.reader_generation == reader_generation
        && now_monotonic_ns >= observation.acquired_monotonic_ns
        && now_monotonic_ns >= observation.rendered_monotonic_ns)
        .then_some(observation)
}

pub(crate) fn retire_receiver_generation(receiver_generation: u64) {
    if let Ok(mut state) = OBSERVATIONS.lock() {
        state
            .pending
            .retain(|_, pending| pending.identity.receiver_generation != receiver_generation);
        if state
            .latest_complete
            .is_some_and(|value| value.identity.receiver_generation == receiver_generation)
        {
            state.latest_complete = None;
        }
    }
}

pub(crate) fn retire_receiver_connection(receiver_generation: u64, connection_generation: u64) {
    if let Ok(mut state) = OBSERVATIONS.lock() {
        state.pending.retain(|_, pending| {
            pending.identity.receiver_generation != receiver_generation
                || pending.identity.connection_generation != connection_generation
        });
        if state.latest_complete.is_some_and(|value| {
            value.identity.receiver_generation == receiver_generation
                && value.identity.connection_generation == connection_generation
        }) {
            state.latest_complete = None;
        }
    }
}

fn observe(
    identity: ReceiverFrameIdentity,
    now_monotonic_ns: u64,
    rendered: bool,
) -> ReceiverFrameObservationResult {
    if !valid_identity(identity) || now_monotonic_ns == 0 {
        return ReceiverFrameObservationResult::IdentityMismatch;
    }
    let frame_key = key(identity);
    let Ok(mut state) = OBSERVATIONS.lock() else {
        return ReceiverFrameObservationResult::IdentityMismatch;
    };
    let Some(pending) = state.pending.get_mut(&frame_key) else {
        return ReceiverFrameObservationResult::Missing;
    };
    if pending.identity != identity || now_monotonic_ns < pending.registered_monotonic_ns {
        return ReceiverFrameObservationResult::IdentityMismatch;
    }
    if rendered {
        if pending.rendered_monotonic_ns != 0 {
            return ReceiverFrameObservationResult::IdentityMismatch;
        }
        pending.rendered_monotonic_ns = now_monotonic_ns;
    } else {
        if pending.acquired_monotonic_ns != 0 {
            return ReceiverFrameObservationResult::IdentityMismatch;
        }
        pending.acquired_monotonic_ns = now_monotonic_ns;
    }
    if pending.rendered_monotonic_ns != 0 && pending.acquired_monotonic_ns != 0 {
        let complete = ReceiverFrameObservation {
            identity: pending.identity,
            registered_monotonic_ns: pending.registered_monotonic_ns,
            rendered_monotonic_ns: pending.rendered_monotonic_ns,
            acquired_monotonic_ns: pending.acquired_monotonic_ns,
        };
        state.pending.remove(&frame_key);
        state.latest_complete = Some(complete);
    }
    ReceiverFrameObservationResult::Accepted
}

fn key(identity: ReceiverFrameIdentity) -> FrameKey {
    FrameKey {
        receiver_generation: identity.receiver_generation,
        connection_generation: identity.connection_generation,
        route_generation: identity.route_generation,
        decoder_token: identity.decoder_token,
        reader_generation: identity.reader_generation,
        presentation_time_ns: identity.presentation_time_ns,
    }
}

fn valid_identity(identity: ReceiverFrameIdentity) -> bool {
    identity.receiver_generation != 0
        && identity.connection_generation != 0
        && identity.route_generation != 0
        && identity.decoder_token != 0
        && identity.reader_generation != 0
        && identity.presentation_time_ns > 0
        && identity.source_elapsed_ns > 0
        && identity.source_unix_ns > 0
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

#[cfg(test)]
mod tests {
    use super::*;

    static TEST_LOCK: Mutex<()> = Mutex::new(());

    fn reset() -> std::sync::MutexGuard<'static, ()> {
        let guard = TEST_LOCK
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let mut state = OBSERVATIONS
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        state.pending.clear();
        state.latest_complete = None;
        drop(state);
        guard
    }

    fn identity(receiver: u64, connection: u64, pts: i64) -> ReceiverFrameIdentity {
        ReceiverFrameIdentity {
            receiver_generation: receiver,
            connection_generation: connection,
            route_generation: 3,
            decoder_token: 4,
            reader_generation: 5,
            presentation_time_ns: pts,
            source_elapsed_ns: 6,
            source_unix_ns: 7,
            pair_id: 8,
            left_source_frame: 9,
            right_source_frame: 10,
            left_sensor_timestamp_ns: 20,
            right_sensor_timestamp_ns: 24,
            pair_delta_ns: 4,
        }
    }

    #[test]
    fn rendered_and_acquired_may_arrive_in_either_order() {
        let _guard = reset();
        let first = identity(101, 1, 11_000);
        assert_eq!(
            register_receiver_frame(first, 100),
            ReceiverFrameRegistrationResult::Accepted
        );
        assert_eq!(
            record_receiver_frame_rendered(first, 102),
            ReceiverFrameObservationResult::Accepted
        );
        assert_eq!(
            record_receiver_frame_acquired(first, 101),
            ReceiverFrameObservationResult::Accepted
        );
        assert_eq!(
            latest_receiver_frame_observation(101, 1, 3, 4, 5, 103)
                .expect("complete first")
                .identity,
            first
        );

        let second = identity(102, 2, 12_000);
        assert_eq!(
            register_receiver_frame(second, 200),
            ReceiverFrameRegistrationResult::Accepted
        );
        assert_eq!(
            record_receiver_frame_acquired(second, 201),
            ReceiverFrameObservationResult::Accepted
        );
        assert_eq!(
            record_receiver_frame_rendered(second, 202),
            ReceiverFrameObservationResult::Accepted
        );
        assert_eq!(
            latest_receiver_frame_observation(102, 2, 3, 4, 5, 203)
                .expect("complete second")
                .identity,
            second
        );
    }

    #[test]
    fn connection_generation_and_exact_pts_are_not_interchangeable() {
        let _guard = reset();
        let current = identity(201, 7, 21_000);
        assert_eq!(
            register_receiver_frame(current, 300),
            ReceiverFrameRegistrationResult::Accepted
        );
        assert_eq!(
            record_receiver_frame_rendered(identity(201, 8, 21_000), 301),
            ReceiverFrameObservationResult::Missing
        );
        let replacement = identity(201, 8, 21_000);
        assert_eq!(
            register_receiver_frame(replacement, 301),
            ReceiverFrameRegistrationResult::Accepted
        );
        assert_eq!(
            record_receiver_frame_acquired(identity(201, 7, 21_001), 301),
            ReceiverFrameObservationResult::Missing
        );
        retire_receiver_connection(201, 7);
        assert_eq!(
            record_receiver_frame_rendered(current, 302),
            ReceiverFrameObservationResult::Missing
        );
        assert_eq!(
            record_receiver_frame_rendered(replacement, 302),
            ReceiverFrameObservationResult::Accepted
        );
        retire_receiver_connection(201, 8);
    }

    #[test]
    fn freshness_requires_every_local_witness_within_the_deadline() {
        let _guard = reset();
        let current = identity(301, 9, 31_000);
        assert_eq!(
            register_receiver_frame(current, 100),
            ReceiverFrameRegistrationResult::Accepted
        );
        assert_eq!(
            record_receiver_frame_rendered(current, 190),
            ReceiverFrameObservationResult::Accepted
        );
        assert_eq!(
            record_receiver_frame_acquired(current, 195),
            ReceiverFrameObservationResult::Accepted
        );
        let observation =
            latest_receiver_frame_observation(301, 9, 3, 4, 5, 195).expect("complete observation");
        assert!(observation.is_fresh_at(199, 100));
        assert!(!observation.is_fresh_at(201, 100));
        assert!(!observation.is_fresh_at(99, 100));
        assert!(!observation.is_fresh_at(199, 0));
    }

    #[test]
    fn timed_witness_uses_one_observation_instant_and_rejects_stale_or_wrong_route() {
        let _guard = reset();
        let current = identity(351, 12, 35_000);
        assert_eq!(
            register_receiver_frame(current, 100),
            ReceiverFrameRegistrationResult::Accepted
        );
        assert_eq!(
            record_receiver_frame_acquired(current, 110),
            ReceiverFrameObservationResult::Accepted
        );
        assert_eq!(
            record_receiver_frame_rendered(current, 120),
            ReceiverFrameObservationResult::Accepted
        );
        assert!(latest_receiver_frame_observation(351, 12, 4, 4, 5, 150).is_none());
        let exact = latest_receiver_frame_observation(351, 12, 3, 4, 5, 150)
            .expect("exact route and acquired frame");
        let timed = exact.timed_at(150, 50).expect("oldest witness at 100");
        assert_eq!(timed.observation.identity, current);
        assert_eq!(timed.observed_at_monotonic_ns, 150);
        assert_eq!(timed.witness_age_ns, 50);
        assert!(exact.timed_at(150, 49).is_none());
        assert!(exact.timed_at(99, 50).is_none());
    }

    #[test]
    fn timed_witness_rejects_future_or_unrepresentable_native_words() {
        let frame = ReceiverFrameObservation {
            identity: identity(352, 13, 36_000),
            registered_monotonic_ns: 100,
            rendered_monotonic_ns: 120,
            acquired_monotonic_ns: 110,
        };
        assert!(frame.timed_at(119, 50).is_none());
        assert!(frame.timed_at(i64::MAX as u64 + 1, u64::MAX).is_none());
        assert!(ReceiverFrameObservation {
            registered_monotonic_ns: 121,
            ..frame
        }
        .timed_at(150, 50)
        .is_none());
        assert!(ReceiverFrameObservation {
            acquired_monotonic_ns: i64::MAX as u64 + 1,
            ..frame
        }
        .timed_at(150, 50)
        .is_none());
    }

    #[test]
    fn capacity_rejection_is_atomic_and_retirement_releases_the_generation() {
        let _guard = reset();
        for index in 0..MAX_PENDING_RECEIVER_FRAMES {
            let mut current = identity(400, 20 + index as u64, 40_000 + index as i64);
            current.pair_id += index as u64;
            assert_eq!(
                register_receiver_frame(current, 10 + index as u64),
                ReceiverFrameRegistrationResult::Accepted
            );
        }
        let rejected = identity(401, 999, 99_000);
        assert_eq!(
            register_receiver_frame(rejected, 999),
            ReceiverFrameRegistrationResult::CapacityExceeded
        );
        assert_eq!(
            record_receiver_frame_rendered(rejected, 1_000),
            ReceiverFrameObservationResult::Missing
        );
        retire_receiver_generation(400);
        assert_eq!(
            register_receiver_frame(rejected, 1_001),
            ReceiverFrameRegistrationResult::Accepted
        );
        retire_receiver_generation(401);
    }
}
