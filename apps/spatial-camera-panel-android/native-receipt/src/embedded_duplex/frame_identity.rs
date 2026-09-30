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

/// Version 2: an exact decoded Surface image, independent of codec callback delivery.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct TimedSurfaceAcquiredFrame {
    pub(crate) identity: ReceiverFrameIdentity,
    pub(crate) registered_monotonic_ns: u64,
    pub(crate) acquired_monotonic_ns: u64,
    pub(crate) observed_at_monotonic_ns: u64,
    pub(crate) witness_age_ns: u64,
}

/// Version 2: the same image was imported and its peer graph submission retired.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct TimedPeerGpuRetiredFrame {
    pub(crate) acquired: TimedSurfaceAcquiredFrame,
    pub(crate) gpu_retired_monotonic_ns: u64,
    pub(crate) witness_age_ns: u64,
    pub(crate) import_sequence: u64,
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
    // Registration occurs before surface release; PTS is an exact lookup key,
    // not a substitute for release order.
    release_ordinal: u64,
    registered_monotonic_ns: u64,
    rendered_monotonic_ns: u64,
    acquired_monotonic_ns: u64,
    gpu_retired_monotonic_ns: u64,
    import_sequence: u64,
}

#[derive(Default)]
struct ObservationState {
    pending: BTreeMap<FrameKey, PendingObservation>,
    // At most 128 exact active streams, removed by connection/generation retirement.
    latest_complete: BTreeMap<FrameKey, Option<(u64, ReceiverFrameObservation)>>,
    latest_acquired: BTreeMap<FrameKey, Option<(u64, PendingObservation)>>,
    latest_effective: BTreeMap<FrameKey, Option<(u64, PendingObservation)>>,
    // Bounded exact history accepts delayed witnesses. No completion is possible
    // without both actual witnesses; evicted history fails closed.
    retired: BTreeMap<FrameKey, PendingObservation>,
    next_release_ordinal: u64,
    counters: ReceiverObservationCounters,
}

/// Process-local diagnostic counters; not part of the authority or JNI schema.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub(crate) struct ReceiverObservationCounters {
    pub(crate) registered: u64,
    pub(crate) rendered: u64,
    pub(crate) acquired: u64,
    pub(crate) completed: u64,
    pub(crate) retired_unacquired: u64,
    /// Retired entries still awaiting their exact render callback.
    pub(crate) retired_unrendered: u64,
    pub(crate) rejected_invalid: u64,
    pub(crate) rejected_duplicate: u64,
    pub(crate) rejected_capacity: u64,
    pub(crate) observation_missing: u64,
    pub(crate) observation_mismatch: u64,
}

pub(crate) fn receiver_observation_counters() -> Option<ReceiverObservationCounters> {
    OBSERVATIONS.lock().ok().map(|state| state.counters)
}

fn stream_key(mut frame: FrameKey) -> FrameKey {
    frame.presentation_time_ns = 0;
    frame
}

fn remember_retired(state: &mut ObservationState, pending: PendingObservation) {
    if !state.retired.contains_key(&key(pending.identity))
        && state.retired.len() >= MAX_PENDING_RECEIVER_FRAMES
    {
        if let Some(oldest) = state
            .retired
            .iter()
            .min_by_key(|(_, value)| value.release_ordinal)
            .map(|(key, _)| *key)
        {
            state.retired.remove(&oldest);
        }
    }
    state.retired.insert(key(pending.identity), pending);
}

static OBSERVATIONS: LazyLock<Mutex<ObservationState>> =
    LazyLock::new(|| Mutex::new(ObservationState::default()));

pub(crate) fn register_receiver_frame(
    identity: ReceiverFrameIdentity,
    now_monotonic_ns: u64,
) -> ReceiverFrameRegistrationResult {
    let Ok(mut state) = OBSERVATIONS.lock() else {
        return ReceiverFrameRegistrationResult::Invalid;
    };
    if !valid_identity(identity) || now_monotonic_ns == 0 {
        state.counters.rejected_invalid = state.counters.rejected_invalid.saturating_add(1);
        return ReceiverFrameRegistrationResult::Invalid;
    }
    let frame_key = key(identity);
    let stream = stream_key(frame_key);
    if state.pending.contains_key(&frame_key)
        || state.retired.contains_key(&frame_key)
        || state.latest_complete.get(&stream).is_some_and(|entry| {
            entry.is_some_and(|(_, observation)| key(observation.identity) == frame_key)
        })
        || state.latest_acquired.get(&stream).is_some_and(|entry| {
            entry.is_some_and(|(_, observation)| key(observation.identity) == frame_key)
        })
    {
        state.counters.rejected_duplicate = state.counters.rejected_duplicate.saturating_add(1);
        return ReceiverFrameRegistrationResult::Duplicate;
    }
    if state.pending.len() >= MAX_PENDING_RECEIVER_FRAMES
        || (!state.latest_complete.contains_key(&stream)
            && state.latest_complete.len() >= MAX_PENDING_RECEIVER_FRAMES)
        || (!state.latest_acquired.contains_key(&stream)
            && state.latest_acquired.len() >= MAX_PENDING_RECEIVER_FRAMES)
        || (!state.latest_effective.contains_key(&stream)
            && state.latest_effective.len() >= MAX_PENDING_RECEIVER_FRAMES)
    {
        state.counters.rejected_capacity = state.counters.rejected_capacity.saturating_add(1);
        return ReceiverFrameRegistrationResult::CapacityExceeded;
    }
    let Some(release_ordinal) = state.next_release_ordinal.checked_add(1) else {
        state.counters.rejected_invalid = state.counters.rejected_invalid.saturating_add(1);
        return ReceiverFrameRegistrationResult::Invalid;
    };
    state.next_release_ordinal = release_ordinal;
    state.latest_complete.entry(stream).or_insert(None);
    state.latest_acquired.entry(stream).or_insert(None);
    state.latest_effective.entry(stream).or_insert(None);
    state.counters.registered = state.counters.registered.saturating_add(1);
    state.pending.insert(
        frame_key,
        PendingObservation {
            identity,
            release_ordinal,
            registered_monotonic_ns: now_monotonic_ns,
            rendered_monotonic_ns: 0,
            acquired_monotonic_ns: 0,
            gpu_retired_monotonic_ns: 0,
            import_sequence: 0,
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

/// Called only after the exact peer common-graph submission and frame fence retire.
pub(crate) fn record_receiver_frame_gpu_retired(
    identity: ReceiverFrameIdentity,
    import_sequence: u64,
    now_monotonic_ns: u64,
) -> ReceiverFrameObservationResult {
    let Ok(mut state) = OBSERVATIONS.lock() else {
        return ReceiverFrameObservationResult::IdentityMismatch;
    };
    if !valid_identity(identity) || import_sequence == 0 || now_monotonic_ns == 0 {
        return ReceiverFrameObservationResult::IdentityMismatch;
    }
    let frame_key = key(identity);
    let pending = if let Some(pending) = state.pending.get_mut(&frame_key) {
        pending
    } else if let Some(pending) = state.retired.get_mut(&frame_key) {
        pending
    } else {
        return ReceiverFrameObservationResult::Missing;
    };
    if pending.identity != identity
        || pending.acquired_monotonic_ns == 0
        || now_monotonic_ns < pending.acquired_monotonic_ns
    {
        return ReceiverFrameObservationResult::IdentityMismatch;
    }
    // Re-presenting one acquired image after another successful fence is
    // idempotent. Keep the first proof instant and exact import sequence.
    if pending.gpu_retired_monotonic_ns != 0 {
        return if pending.import_sequence == import_sequence
            && now_monotonic_ns >= pending.gpu_retired_monotonic_ns
        {
            ReceiverFrameObservationResult::Accepted
        } else {
            ReceiverFrameObservationResult::IdentityMismatch
        };
    }
    pending.gpu_retired_monotonic_ns = now_monotonic_ns;
    pending.import_sequence = import_sequence;
    let recorded = *pending;
    let latest = state
        .latest_effective
        .entry(stream_key(frame_key))
        .or_default();
    if latest.map_or(true, |(ordinal, _)| ordinal < recorded.release_ordinal) {
        *latest = Some((recorded.release_ordinal, recorded));
    }
    ReceiverFrameObservationResult::Accepted
}

pub(crate) fn latest_surface_acquired_timed(
    receiver_generation: u64,
    connection_generation: u64,
    route_generation: u64,
    decoder_token: u64,
    reader_generation: u64,
    now_monotonic_ns: u64,
    max_age_ns: u64,
) -> Option<TimedSurfaceAcquiredFrame> {
    let state = OBSERVATIONS.lock().ok()?;
    let pending = state
        .latest_acquired
        .get(&FrameKey {
            receiver_generation,
            connection_generation,
            route_generation,
            decoder_token,
            reader_generation,
            presentation_time_ns: 0,
        })?
        .as_ref()?
        .1;
    timed_surface_acquired(pending, now_monotonic_ns, max_age_ns)
}

pub(crate) fn latest_peer_gpu_retired_timed(
    receiver_generation: u64,
    connection_generation: u64,
    route_generation: u64,
    decoder_token: u64,
    reader_generation: u64,
    now_monotonic_ns: u64,
    max_age_ns: u64,
) -> Option<TimedPeerGpuRetiredFrame> {
    let state = OBSERVATIONS.lock().ok()?;
    let pending = state
        .latest_effective
        .get(&FrameKey {
            receiver_generation,
            connection_generation,
            route_generation,
            decoder_token,
            reader_generation,
            presentation_time_ns: 0,
        })?
        .as_ref()?
        .1;
    let acquired = timed_surface_acquired(pending, now_monotonic_ns, max_age_ns)?;
    if pending.gpu_retired_monotonic_ns < acquired.acquired_monotonic_ns
        || pending.gpu_retired_monotonic_ns > now_monotonic_ns
        || pending.import_sequence == 0
    {
        return None;
    }
    Some(TimedPeerGpuRetiredFrame {
        acquired,
        gpu_retired_monotonic_ns: pending.gpu_retired_monotonic_ns,
        witness_age_ns: now_monotonic_ns.checked_sub(acquired.registered_monotonic_ns)?,
        import_sequence: pending.import_sequence,
    })
}

fn timed_surface_acquired(
    pending: PendingObservation,
    now_monotonic_ns: u64,
    max_age_ns: u64,
) -> Option<TimedSurfaceAcquiredFrame> {
    if max_age_ns == 0
        || pending.registered_monotonic_ns == 0
        || pending.acquired_monotonic_ns < pending.registered_monotonic_ns
        || pending.acquired_monotonic_ns > now_monotonic_ns
    {
        return None;
    }
    let age = now_monotonic_ns.checked_sub(pending.registered_monotonic_ns)?;
    (age <= max_age_ns).then_some(TimedSurfaceAcquiredFrame {
        identity: pending.identity,
        registered_monotonic_ns: pending.registered_monotonic_ns,
        acquired_monotonic_ns: pending.acquired_monotonic_ns,
        observed_at_monotonic_ns: now_monotonic_ns,
        witness_age_ns: age,
    })
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
    let observation = state
        .latest_complete
        .get(&FrameKey {
            receiver_generation,
            connection_generation,
            route_generation,
            decoder_token,
            reader_generation,
            presentation_time_ns: 0,
        })?
        .as_ref()?
        .1;
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
        state
            .latest_complete
            .retain(|key, _| key.receiver_generation != receiver_generation);
        state
            .latest_acquired
            .retain(|key, _| key.receiver_generation != receiver_generation);
        state
            .latest_effective
            .retain(|key, _| key.receiver_generation != receiver_generation);
        state
            .retired
            .retain(|key, _| key.receiver_generation != receiver_generation);
    }
}

pub(crate) fn retire_receiver_connection(receiver_generation: u64, connection_generation: u64) {
    if let Ok(mut state) = OBSERVATIONS.lock() {
        state.pending.retain(|_, pending| {
            pending.identity.receiver_generation != receiver_generation
                || pending.identity.connection_generation != connection_generation
        });
        state.latest_complete.retain(|key, _| {
            key.receiver_generation != receiver_generation
                || key.connection_generation != connection_generation
        });
        state.latest_acquired.retain(|key, _| {
            key.receiver_generation != receiver_generation
                || key.connection_generation != connection_generation
        });
        state.latest_effective.retain(|key, _| {
            key.receiver_generation != receiver_generation
                || key.connection_generation != connection_generation
        });
        state.retired.retain(|key, _| {
            key.receiver_generation != receiver_generation
                || key.connection_generation != connection_generation
        });
    }
}

fn observe(
    identity: ReceiverFrameIdentity,
    now_monotonic_ns: u64,
    rendered: bool,
) -> ReceiverFrameObservationResult {
    let Ok(mut state) = OBSERVATIONS.lock() else {
        return ReceiverFrameObservationResult::IdentityMismatch;
    };
    if !valid_identity(identity) || now_monotonic_ns == 0 {
        state.counters.observation_mismatch = state.counters.observation_mismatch.saturating_add(1);
        return ReceiverFrameObservationResult::IdentityMismatch;
    }
    let frame_key = key(identity);
    // A native reader callback may consume A's source identity before B but
    // deliver A's observation after B on another thread. Retained exact history
    // accepts that actual acquire once, just as it accepts a delayed render.
    let pending = if state.pending.contains_key(&frame_key) {
        state.pending.get_mut(&frame_key)
    } else {
        state.retired.get_mut(&frame_key)
    };
    let Some(pending) = pending else {
        state.counters.observation_missing = state.counters.observation_missing.saturating_add(1);
        return ReceiverFrameObservationResult::Missing;
    };
    if pending.identity != identity || now_monotonic_ns < pending.registered_monotonic_ns {
        state.counters.observation_mismatch = state.counters.observation_mismatch.saturating_add(1);
        return ReceiverFrameObservationResult::IdentityMismatch;
    }
    if rendered {
        if pending.rendered_monotonic_ns != 0 {
            state.counters.observation_mismatch =
                state.counters.observation_mismatch.saturating_add(1);
            return ReceiverFrameObservationResult::IdentityMismatch;
        }
        pending.rendered_monotonic_ns = now_monotonic_ns;
    } else {
        if pending.acquired_monotonic_ns != 0 {
            state.counters.observation_mismatch =
                state.counters.observation_mismatch.saturating_add(1);
            return ReceiverFrameObservationResult::IdentityMismatch;
        }
        pending.acquired_monotonic_ns = now_monotonic_ns;
    }
    let pending = *pending;
    if rendered {
        state.counters.rendered = state.counters.rendered.saturating_add(1);
    } else {
        state.counters.acquired = state.counters.acquired.saturating_add(1);
        let latest = state
            .latest_acquired
            .entry(stream_key(frame_key))
            .or_default();
        if latest.map_or(true, |(ordinal, _)| ordinal < pending.release_ordinal) {
            *latest = Some((pending.release_ordinal, pending));
        }
        // A real acquireLatestImage witness retires unwitnessed predecessors
        // by release order. Older acquired entries also move to bounded exact
        // history so callback-free streams do not fill the pending registry.
        // Late callbacks and GPU retirements can still join retained entries.
        let predecessors: Vec<_> = state
            .pending
            .iter()
            .filter_map(|(key, other)| {
                (stream_key(*key) == stream_key(frame_key)
                    && other.release_ordinal < pending.release_ordinal)
                    .then_some(*key)
            })
            .collect();
        for predecessor in predecessors {
            let skipped = state
                .pending
                .remove(&predecessor)
                .expect("retained predecessor");
            if skipped.acquired_monotonic_ns == 0 {
                state.counters.retired_unacquired =
                    state.counters.retired_unacquired.saturating_add(1);
            }
            if skipped.rendered_monotonic_ns == 0 {
                state.counters.retired_unrendered =
                    state.counters.retired_unrendered.saturating_add(1);
            }
            remember_retired(&mut state, skipped);
        }
    }
    if pending.rendered_monotonic_ns != 0 && pending.acquired_monotonic_ns != 0 {
        let complete = ReceiverFrameObservation {
            identity: pending.identity,
            registered_monotonic_ns: pending.registered_monotonic_ns,
            rendered_monotonic_ns: pending.rendered_monotonic_ns,
            acquired_monotonic_ns: pending.acquired_monotonic_ns,
        };
        state.pending.remove(&frame_key);
        state.counters.completed = state.counters.completed.saturating_add(1);
        let latest = state
            .latest_complete
            .entry(stream_key(frame_key))
            .or_default();
        if latest.map_or(true, |(ordinal, _)| ordinal < pending.release_ordinal) {
            *latest = Some((pending.release_ordinal, complete));
        }
        remember_retired(&mut state, pending);
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

/// Offscreen Peer-bank priming is permitted only for an exact attached,
/// pending embedded Peer route. The display-origin policy is not changed.
pub(crate) fn source_set_peer_priming_required(
    demanded_prefix: usize,
    peer: Option<(u64, u64, u64, u64)>, // receiver, route, decoder, reader
    binding_current: bool,
    route_words: &[i64; 16],
    source_peer: i64,
    result_pending: i64,
    common_graph_stage: i64,
) -> bool {
    let Some((receiver, route, decoder, reader)) = peer else {
        return false;
    };
    let (Ok(route), Ok(decoder), Ok(reader)) = (
        i64::try_from(route),
        i64::try_from(decoder),
        i64::try_from(reader),
    ) else {
        return false;
    };
    demanded_prefix == 0
        && receiver != 0
        && binding_current
        && common_graph_stage > 0
        && route_words[1] == route
        && route_words[2] == source_peer
        && route_words[3] == decoder
        && route_words[4] == reader
        && route_words[11] == result_pending
        && route_words[10] & common_graph_stage != 0
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
        *state = ObservationState::default();
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

    fn register(frame: ReceiverFrameIdentity, at: u64) {
        assert_eq!(
            register_receiver_frame(frame, at),
            ReceiverFrameRegistrationResult::Accepted
        );
    }

    fn render(frame: ReceiverFrameIdentity, at: u64) {
        assert_eq!(
            record_receiver_frame_rendered(frame, at),
            ReceiverFrameObservationResult::Accepted
        );
    }

    fn acquire(frame: ReceiverFrameIdentity, at: u64) {
        assert_eq!(
            record_receiver_frame_acquired(frame, at),
            ReceiverFrameObservationResult::Accepted
        );
    }

    #[test]
    fn sustained_reader_skips_retire_without_exhausting_the_pending_window() {
        let _guard = reset();
        for n in 1..=4096_i64 {
            let frame = identity(501, 1, n * 1000);
            let at = n as u64 * 10;
            register(frame, at);
            render(frame, at + 1);
            if n % 3 == 0 {
                acquire(frame, at + 2);
            }
        }
        let state = OBSERVATIONS.lock().unwrap();
        assert_eq!(state.pending.len(), 1);
        assert_eq!(state.retired.len(), MAX_PENDING_RECEIVER_FRAMES);
        assert_eq!(state.counters.rejected_capacity, 0);
        assert_eq!(state.counters.retired_unacquired, 2730);
        assert_eq!(state.counters.completed, 1365);
    }

    #[test]
    fn acquired_predecessor_survives_reordered_render_without_regressing_latest() {
        let _guard = reset();
        // PTS order differs from surface release order.
        let a = identity(502, 1, 9000);
        let b = identity(502, 1, 1000);
        register(a, 10);
        register(b, 11);
        acquire(a, 12);
        render(b, 13);
        assert!(OBSERVATIONS.lock().unwrap().pending.contains_key(&key(a)));
        acquire(b, 14);
        render(a, 15);
        assert_eq!(
            latest_receiver_frame_observation(502, 1, 3, 4, 5, 16)
                .unwrap()
                .identity,
            b
        );
        assert_eq!(receiver_observation_counters().unwrap().completed, 2);
        assert_eq!(
            record_receiver_frame_rendered(a, 17),
            ReceiverFrameObservationResult::IdentityMismatch
        );
    }

    #[test]
    fn render_alone_does_not_retire_or_infer_acquisition_for_predecessors() {
        let _guard = reset();
        let a = identity(503, 1, 1000);
        let b = identity(503, 1, 2000);
        register(a, 10);
        register(b, 11);
        render(b, 12);
        acquire(a, 13);
        render(a, 14);
        assert_eq!(
            latest_receiver_frame_observation(503, 1, 3, 4, 5, 15)
                .unwrap()
                .identity,
            a
        );
        assert_eq!(
            receiver_observation_counters().unwrap().retired_unacquired,
            0
        );
        acquire(b, 16);
        assert_eq!(
            latest_receiver_frame_observation(503, 1, 3, 4, 5, 17)
                .unwrap()
                .identity,
            b
        );
    }

    #[test]
    fn skipped_identity_accepts_one_exact_late_render_without_inferred_acquisition() {
        let _guard = reset();
        let a = identity(504, 1, 1000);
        let b = identity(504, 1, 2000);
        register(a, 10);
        register(b, 11);
        acquire(b, 12);
        let mut wrong = a;
        wrong.pair_id += 1;
        assert_eq!(
            record_receiver_frame_rendered(wrong, 13),
            ReceiverFrameObservationResult::IdentityMismatch
        );
        assert_eq!(
            record_receiver_frame_rendered(a, 9),
            ReceiverFrameObservationResult::IdentityMismatch
        );
        render(a, 14);
        assert!(latest_receiver_frame_observation(504, 1, 3, 4, 5, 15).is_none());
        assert_eq!(
            record_receiver_frame_rendered(a, 16),
            ReceiverFrameObservationResult::IdentityMismatch
        );
        assert_eq!(
            register_receiver_frame(a, 17),
            ReceiverFrameRegistrationResult::Duplicate
        );
        render(b, 18);
        assert_eq!(
            latest_receiver_frame_observation(504, 1, 3, 4, 5, 19)
                .unwrap()
                .identity,
            b
        );
        assert_eq!(receiver_observation_counters().unwrap().completed, 1);
    }

    #[test]
    fn reordered_exact_acquisition_can_complete_from_history_without_regressing_latest() {
        let _guard = reset();
        let a = identity(512, 1, 1000);
        let b = identity(512, 1, 2000);
        register(a, 10);
        register(b, 11);
        render(a, 12);
        // Native source consumption for A already happened, but its actual
        // acquisition witness is delivered after B's on another callback thread.
        acquire(b, 14);
        render(b, 15);
        acquire(a, 13);
        assert_eq!(
            latest_receiver_frame_observation(512, 1, 3, 4, 5, 16)
                .unwrap()
                .identity,
            b
        );
        assert_eq!(
            record_receiver_frame_acquired(a, 17),
            ReceiverFrameObservationResult::IdentityMismatch
        );
        assert_eq!(receiver_observation_counters().unwrap().completed, 2);
    }

    #[test]
    fn interleaved_streams_preserve_exact_pending_and_completed_witnesses() {
        let _guard = reset();
        let a = identity(505, 1, 1000);
        let b = identity(505, 1, 2000);
        let mut others = [a; 5];
        others[0].receiver_generation += 1;
        others[1].connection_generation += 1;
        others[2].route_generation += 1;
        others[3].decoder_token += 1;
        others[4].reader_generation += 1;
        register(a, 10);
        register(b, 11);
        for frame in others {
            register(frame, 12);
        }
        acquire(b, 13);
        // Each foreign stream acquires before skipped A's render arrives.
        for frame in others {
            acquire(frame, 14);
            render(frame, 15);
        }
        render(a, 16);
        assert!(latest_receiver_frame_observation(505, 1, 3, 4, 5, 17).is_none());
        render(b, 18);
        for frame in others.into_iter().chain([b]) {
            assert_eq!(
                latest_receiver_frame_observation(
                    frame.receiver_generation,
                    frame.connection_generation,
                    frame.route_generation,
                    frame.decoder_token,
                    frame.reader_generation,
                    19
                )
                .unwrap()
                .identity,
                frame
            );
        }
        assert_eq!(
            receiver_observation_counters().unwrap().retired_unacquired,
            1
        );
    }

    #[test]
    fn connection_and_generation_retirement_clear_pending_history_and_latest_only_for_owner() {
        let _guard = reset();
        let a = identity(507, 1, 1000);
        let b = identity(507, 1, 2000);
        let c = identity(507, 1, 3000);
        let other_connection = identity(507, 2, 1000);
        let other_receiver = identity(508, 1, 1000);
        for frame in [a, b, c, other_connection, other_receiver] {
            register(frame, 10);
        }
        acquire(b, 11);
        render(b, 12);
        retire_receiver_connection(507, 1);
        for frame in [a, b, c] {
            assert_eq!(
                record_receiver_frame_rendered(frame, 13),
                ReceiverFrameObservationResult::Missing
            );
        }
        assert!(latest_receiver_frame_observation(507, 1, 3, 4, 5, 14).is_none());
        acquire(other_connection, 15);
        render(other_connection, 16);
        retire_receiver_generation(507);
        assert_eq!(
            record_receiver_frame_rendered(other_connection, 17),
            ReceiverFrameObservationResult::Missing
        );
        assert!(latest_receiver_frame_observation(507, 2, 3, 4, 5, 18).is_none());
        acquire(other_receiver, 19);
        render(other_receiver, 20);
        assert_eq!(
            latest_receiver_frame_observation(508, 1, 3, 4, 5, 21)
                .unwrap()
                .identity,
            other_receiver
        );
    }

    #[test]
    fn absent_skipped_callbacks_have_bounded_history_and_fail_closed_after_eviction() {
        let _guard = reset();
        for n in 1..=4096_i64 {
            let frame = identity(509, 1, n * 1000);
            register(frame, n as u64 * 10);
            if n % 2 == 0 {
                acquire(frame, n as u64 * 10 + 1);
                render(frame, n as u64 * 10 + 2);
            }
        }
        let last_skip = identity(509, 1, 4095_000);
        render(last_skip, 50_000);
        assert_eq!(
            record_receiver_frame_rendered(identity(509, 1, 1000), 50_001),
            ReceiverFrameObservationResult::Missing
        );
        let state = OBSERVATIONS.lock().unwrap();
        assert!(state.pending.is_empty());
        assert_eq!(state.retired.len(), MAX_PENDING_RECEIVER_FRAMES);
        assert_eq!(state.counters.completed, 2048);
        assert_eq!(state.counters.retired_unacquired, 2048);
        assert_eq!(state.counters.retired_unrendered, 2048);
        assert_eq!(state.counters.rejected_capacity, 0);
    }

    #[test]
    fn callback_free_acquisitions_remain_bounded_beyond_two_thousand_frames() {
        let _guard = reset();
        for n in 1..=4096_i64 {
            let frame = identity(510, 1, n * 1000);
            register(frame, n as u64 * 10);
            acquire(frame, n as u64 * 10 + 1);
        }
        let latest = latest_surface_acquired_timed(510, 1, 3, 4, 5, 40_962, 50_000)
            .expect("latest exact acquired image");
        assert_eq!(latest.identity.presentation_time_ns, 4_096_000);
        assert!(latest_peer_gpu_retired_timed(510, 1, 3, 4, 5, 40_962, 50_000).is_none());
        let state = OBSERVATIONS.lock().unwrap();
        assert_eq!(state.pending.len(), 1);
        assert_eq!(state.retired.len(), MAX_PENDING_RECEIVER_FRAMES);
        assert_eq!(state.counters.rejected_capacity, 0);
        assert_eq!(state.counters.completed, 0);
        drop(state);
        assert_eq!(
            record_receiver_frame_rendered(identity(510, 1, 1000), 40_963),
            ReceiverFrameObservationResult::Missing
        );
        render(identity(510, 1, 4_095_000), 40_964);
        assert!(
            latest_receiver_frame_observation(510, 1, 3, 4, 5, 40_965)
                .unwrap()
                .identity
                .presentation_time_ns
                == 4_095_000
        );
        assert_eq!(
            latest_surface_acquired_timed(510, 1, 3, 4, 5, 40_965, 50_000)
                .unwrap()
                .identity
                .presentation_time_ns,
            4_096_000
        );
    }

    #[test]
    fn gpu_retirement_requires_same_exact_acquired_frame_and_one_positive_import() {
        let _guard = reset();
        let a = identity(513, 1, 1000);
        let b = identity(513, 1, 2000);
        register(a, 10);
        register(b, 11);
        assert_eq!(
            record_receiver_frame_gpu_retired(a, 3, 12),
            ReceiverFrameObservationResult::IdentityMismatch
        );
        acquire(a, 12);
        let mut wrong = a;
        wrong.pair_id += 1;
        assert_eq!(
            record_receiver_frame_gpu_retired(wrong, 3, 13),
            ReceiverFrameObservationResult::IdentityMismatch
        );
        assert_eq!(
            record_receiver_frame_gpu_retired(a, 0, 13),
            ReceiverFrameObservationResult::IdentityMismatch
        );
        assert_eq!(
            record_receiver_frame_gpu_retired(a, 3, 11),
            ReceiverFrameObservationResult::IdentityMismatch
        );
        assert!(latest_peer_gpu_retired_timed(513, 1, 3, 4, 5, 13, 100).is_none());
        acquire(b, 14);
        assert_eq!(
            record_receiver_frame_gpu_retired(a, 3, 15),
            ReceiverFrameObservationResult::Accepted
        );
        assert_eq!(
            record_receiver_frame_gpu_retired(a, 3, 16),
            ReceiverFrameObservationResult::Accepted
        );
        assert_eq!(
            record_receiver_frame_gpu_retired(a, 4, 16),
            ReceiverFrameObservationResult::IdentityMismatch
        );
        assert_eq!(
            record_receiver_frame_gpu_retired(a, 3, 14),
            ReceiverFrameObservationResult::IdentityMismatch
        );
        assert_eq!(
            latest_peer_gpu_retired_timed(513, 1, 3, 4, 5, 16, 100)
                .unwrap()
                .gpu_retired_monotonic_ns,
            15
        );
        assert_eq!(
            latest_peer_gpu_retired_timed(513, 1, 3, 4, 5, 16, 100)
                .unwrap()
                .acquired
                .identity,
            a
        );
        assert_eq!(
            record_receiver_frame_gpu_retired(b, 4, 17),
            ReceiverFrameObservationResult::Accepted
        );
        assert_eq!(
            latest_peer_gpu_retired_timed(513, 1, 3, 4, 5, 18, 100)
                .unwrap()
                .acquired
                .identity,
            b
        );
        render(a, 19);
        assert_eq!(
            latest_peer_gpu_retired_timed(513, 1, 3, 4, 5, 20, 100)
                .unwrap()
                .acquired
                .identity,
            b
        );
    }

    #[test]
    fn acquired_and_effective_proofs_expire_and_retire_by_binding() {
        let _guard = reset();
        let a = identity(514, 1, 1000);
        let b = identity(514, 2, 1000);
        register(a, 10);
        register(b, 11);
        acquire(a, 12);
        acquire(b, 13);
        assert_eq!(
            record_receiver_frame_gpu_retired(a, 1, 14),
            ReceiverFrameObservationResult::Accepted
        );
        assert_eq!(
            record_receiver_frame_gpu_retired(b, 2, 15),
            ReceiverFrameObservationResult::Accepted
        );
        assert!(latest_surface_acquired_timed(514, 1, 3, 4, 6, 16, 100).is_none());
        assert!(latest_peer_gpu_retired_timed(514, 1, 3, 4, 5, 112, 100).is_none());
        retire_receiver_connection(514, 1);
        assert!(latest_surface_acquired_timed(514, 1, 3, 4, 5, 16, 100).is_none());
        assert!(latest_peer_gpu_retired_timed(514, 1, 3, 4, 5, 16, 100).is_none());
        assert_eq!(
            record_receiver_frame_gpu_retired(a, 3, 17),
            ReceiverFrameObservationResult::Missing
        );
        assert_eq!(
            latest_peer_gpu_retired_timed(514, 2, 3, 4, 5, 16, 100)
                .unwrap()
                .acquired
                .identity,
            b
        );
        retire_receiver_generation(514);
        assert!(latest_peer_gpu_retired_timed(514, 2, 3, 4, 5, 16, 100).is_none());
    }

    #[test]
    fn own_only_policy_primes_only_an_attached_current_pending_embedded_peer() {
        let _guard = reset();
        let mut route = [0_i64; 16];
        route[1] = 3;
        route[2] = 2;
        route[3] = 4;
        route[4] = 5;
        route[10] = 512;
        let peer = Some((7, 3, 4, 5));
        let prime = |demand, candidate, bound, words: &[i64; 16]| {
            source_set_peer_priming_required(demand, candidate, bound, words, 2, 0, 512)
        };
        assert!(prime(0, peer, true, &route));
        assert!(!prime(6, peer, true, &route)); // already demanded by Mixed policy
        assert!(!prime(0, None, true, &route)); // no retained Peer lease
        assert!(!prime(0, Some((0, 3, 4, 5)), true, &route)); // ordinary Peer
        assert!(!prime(0, peer, false, &route)); // decoder/reader rebind
        route[10] = 0;
        assert!(!prime(0, peer, true, &route)); // graph has not attached
        route[10] = 512;
        route[11] = 1;
        assert!(!prime(0, peer, true, &route)); // already effective
        route[11] = 4;
        assert!(!prime(0, peer, true, &route)); // lost or stopped route
        route[11] = 0;
        route[2] = 1;
        assert!(!prime(0, peer, true, &route)); // foreign source
        route[2] = 2;
        route[3] = 6;
        assert!(!prime(0, peer, true, &route)); // foreign decoder
    }

    #[test]
    fn ordinal_exhaustion_rejects_without_publishing_or_wrapping() {
        let _guard = reset();
        OBSERVATIONS.lock().unwrap().next_release_ordinal = u64::MAX;
        let frame = identity(511, 1, 1000);
        assert_eq!(
            register_receiver_frame(frame, 10),
            ReceiverFrameRegistrationResult::Invalid
        );
        assert_eq!(
            record_receiver_frame_rendered(frame, 11),
            ReceiverFrameObservationResult::Missing
        );
        let state = OBSERVATIONS.lock().unwrap();
        assert!(state.pending.is_empty());
        assert!(state.latest_complete.is_empty());
        assert_eq!(state.next_release_ordinal, u64::MAX);
    }
}
