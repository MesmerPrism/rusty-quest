//! Generation-scoped source-owner state for Unit020 projection routing.
//!
//! This module deliberately contains no Manifold control call.  The packed-media
//! producer identity reported here identifies the active decoder/reader ingress;
//! it is not proof that a separate Manifold broker process has stopped.

use std::sync::{LazyLock, Mutex};

pub(crate) const SOURCE_ABI_VERSION: i64 = 1;
pub(crate) const SOURCE_ABI_WORDS: usize = 16;

pub(crate) const SOURCE_DISABLED: i64 = 0;
pub(crate) const SOURCE_LOCAL: i64 = 1;
pub(crate) const SOURCE_PEER: i64 = 2;

pub(crate) const STAGE_PROVIDER: i64 = 1;
pub(crate) const STAGE_DECODER: i64 = 2;
pub(crate) const STAGE_READER: i64 = 4;
pub(crate) const STAGE_SURFACE: i64 = 8;
pub(crate) const STAGE_RETIRED_PAIR: i64 = 16;
pub(crate) const STAGE_IMPORT: i64 = 32;
pub(crate) const STAGE_ACTIVE: i64 = 64;
pub(crate) const STAGE_STOPPED: i64 = 128;
pub(crate) const STAGE_PRODUCER_INACTIVE: i64 = 256;
pub(crate) const STAGE_COMMON_GRAPH: i64 = 512;

pub(crate) const RESULT_PENDING: i64 = 0;
pub(crate) const RESULT_EFFECTIVE: i64 = 1;
pub(crate) const RESULT_INACTIVE: i64 = 2;
pub(crate) const RESULT_REJECTED: i64 = 3;
pub(crate) const RESULT_LOST: i64 = 4;
pub(crate) const RESULT_UNAVAILABLE: i64 = 5;

pub(crate) const REASON_NONE: i64 = 0;
pub(crate) const REASON_CARRIER_UNAVAILABLE: i64 = 1;
pub(crate) const REASON_PROVIDER_REJECTED: i64 = 2;
pub(crate) const REASON_PEER_SETTINGS_INVALID: i64 = 3;
pub(crate) const REASON_CLEANUP_REQUIRED: i64 = 4;
pub(crate) const REASON_CLEANUP_FOREIGN: i64 = 5;
pub(crate) const REASON_CLEANUP_DENIED: i64 = 6;
pub(crate) const REASON_DECODER_REPLACEMENT_FAILED: i64 = 7;
pub(crate) const REASON_STOP_FAILED: i64 = 8;
pub(crate) const REASON_RECEIPT_UNAVAILABLE: i64 = 9;
pub(crate) const REASON_RECEIPT_FOREIGN: i64 = 10;
pub(crate) const REASON_STALE: i64 = 11;
pub(crate) const REASON_ACTIVE_LOST: i64 = 12;
pub(crate) const REASON_MALFORMED: i64 = 13;

const PEER_EFFECTIVE_STAGES: i64 = STAGE_PROVIDER
    | STAGE_DECODER
    | STAGE_READER
    | STAGE_SURFACE
    | STAGE_RETIRED_PAIR
    | STAGE_IMPORT
    | STAGE_ACTIVE
    | STAGE_COMMON_GRAPH;
const LOCAL_EFFECTIVE_STAGES: i64 =
    STAGE_PROVIDER | STAGE_READER | STAGE_SURFACE | STAGE_IMPORT | STAGE_ACTIVE;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct SourceReceipt {
    pub(crate) words: [i64; SOURCE_ABI_WORDS],
}

impl SourceReceipt {
    fn new(request: SourceRequest, result: i64, reason: i64, stages: i64) -> Self {
        Self {
            words: [
                SOURCE_ABI_VERSION,
                request.route_generation,
                request.source,
                request.decoder_token,
                request.reader_generation,
                request.launch_challenge,
                request.surface_generation,
                request.pair_generation,
                request.import_generation,
                request.acquisition_time_ns,
                stages,
                result,
                reason,
                i64::from(request.camera_start_allowed),
                request.producer_session,
                request.producer_epoch,
            ],
        }
    }

    pub(crate) fn unavailable(route_generation: i64, reason: i64) -> Self {
        Self {
            words: [
                SOURCE_ABI_VERSION,
                route_generation,
                SOURCE_DISABLED,
                0,
                0,
                0,
                0,
                0,
                0,
                monotonic_now_ns(),
                0,
                RESULT_UNAVAILABLE,
                reason,
                0,
                0,
                0,
            ],
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct SourceRequest {
    route_generation: i64,
    source: i64,
    decoder_token: i64,
    reader_generation: i64,
    launch_challenge: i64,
    surface_generation: i64,
    pair_generation: i64,
    import_generation: i64,
    acquisition_time_ns: i64,
    required_stages: i64,
    camera_start_allowed: bool,
    producer_session: i64,
    producer_epoch: i64,
}

impl SourceRequest {
    fn decode(words: [i64; SOURCE_ABI_WORDS]) -> Result<Self, i64> {
        if words[0] != SOURCE_ABI_VERSION
            || words[1] <= 0
            || !matches!(words[2], SOURCE_DISABLED | SOURCE_LOCAL | SOURCE_PEER)
            || words[5] <= 0
            || words[6] <= 0
            || !matches!(words[13], 0 | 1)
        {
            return Err(REASON_MALFORMED);
        }
        let expected_stages = match words[2] {
            SOURCE_DISABLED => STAGE_STOPPED,
            SOURCE_LOCAL => LOCAL_EFFECTIVE_STAGES,
            SOURCE_PEER => PEER_EFFECTIVE_STAGES,
            _ => unreachable!(),
        };
        if words[10] != expected_stages
            || (words[2] == SOURCE_LOCAL) != (words[13] == 1)
            || words[3] != 0
            || words[4] != 0
            || words[7] != 0
            || words[8] != 0
            || words[9] != 0
            || words[11] != RESULT_PENDING
            || words[12] != REASON_NONE
            || words[14] != 0
            || words[15] != 0
        {
            return Err(REASON_MALFORMED);
        }
        Ok(Self {
            route_generation: words[1],
            source: words[2],
            decoder_token: 0,
            reader_generation: 0,
            launch_challenge: words[5],
            surface_generation: words[6],
            pair_generation: 0,
            import_generation: 0,
            acquisition_time_ns: 0,
            required_stages: words[10],
            camera_start_allowed: words[13] == 1,
            producer_session: 0,
            producer_epoch: 0,
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct PeerFrameWitness {
    pub(crate) decoder_token: u64,
    pub(crate) reader_generation: u64,
    pub(crate) pair_generation: u64,
    pub(crate) import_generation: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct QualifiedProducerInactiveWitness {
    route_generation: i64,
    producer_session: i64,
    producer_epoch: i64,
}

#[derive(Default)]
struct SourceOwnerState {
    receipt: Option<SourceReceipt>,
    inactive_witness: Option<QualifiedProducerInactiveWitness>,
}

static SOURCE_OWNER: LazyLock<Mutex<SourceOwnerState>> =
    LazyLock::new(|| Mutex::new(SourceOwnerState::default()));

pub(crate) fn request_source(
    words: [i64; SOURCE_ABI_WORDS],
    carrier_available: bool,
) -> SourceReceipt {
    let route_generation = words[1];
    let request = match SourceRequest::decode(words) {
        Ok(request) => request,
        Err(reason) => return SourceReceipt::unavailable(route_generation, reason),
    };
    let mut owner = SOURCE_OWNER
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if owner
        .receipt
        .is_some_and(|receipt| receipt.words[1] >= request.route_generation)
    {
        return SourceReceipt::new(request, RESULT_REJECTED, REASON_RECEIPT_FOREIGN, 0);
    }
    let receipt = if !carrier_available {
        SourceReceipt::new(request, RESULT_UNAVAILABLE, REASON_CARRIER_UNAVAILABLE, 0)
    } else if request.source == SOURCE_DISABLED {
        // Requesting stop is not retirement.  The renderer/acquisition owner must publish
        // `record_acquisition_stopped` only after its worker and submitted frame retire.
        SourceReceipt::new(request, RESULT_PENDING, REASON_NONE, 0)
    } else {
        SourceReceipt::new(
            request,
            RESULT_PENDING,
            REASON_NONE,
            STAGE_PROVIDER | STAGE_SURFACE,
        )
    };
    owner.receipt = Some(receipt);
    owner.inactive_witness = None;
    receipt
}

pub(crate) fn pending_route(
    route_generation: i64,
    source: i64,
    launch_challenge: i64,
    surface_generation: i64,
) -> bool {
    SOURCE_OWNER
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .receipt
        .is_some_and(|receipt| {
            receipt.words[1] == route_generation
                && receipt.words[2] == source
                && receipt.words[5] == launch_challenge
                && receipt.words[6] == surface_generation
                && receipt.words[11] == RESULT_PENDING
        })
}

pub(crate) fn pending_route_generation(
    source: i64,
    launch_challenge: i64,
    surface_generation: i64,
) -> Option<i64> {
    SOURCE_OWNER
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .receipt
        .and_then(|receipt| {
            (receipt.words[2] == source
                && receipt.words[5] == launch_challenge
                && receipt.words[6] == surface_generation
                && receipt.words[11] == RESULT_PENDING)
                .then_some(receipt.words[1])
        })
}

pub(crate) fn record_peer_submission_retired(
    route_generation: i64,
    witness: PeerFrameWitness,
) -> SourceReceipt {
    let mut owner = SOURCE_OWNER
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(current) = owner.receipt else {
        return SourceReceipt::unavailable(route_generation, REASON_RECEIPT_UNAVAILABLE);
    };
    if current.words[1] != route_generation || current.words[2] != SOURCE_PEER {
        return SourceReceipt::unavailable(route_generation, REASON_RECEIPT_FOREIGN);
    }
    if current.words[10] & STAGE_COMMON_GRAPH == 0 {
        drop(owner);
        return mark_route_lost(route_generation, REASON_MALFORMED);
    }
    if witness.decoder_token == 0
        || witness.reader_generation == 0
        || witness.pair_generation == 0
        || witness.import_generation == 0
    {
        let lost = SourceReceipt::new(
            request_from_receipt(current),
            RESULT_LOST,
            REASON_MALFORMED,
            current.words[10] & !STAGE_ACTIVE,
        );
        owner.receipt = Some(lost);
        return lost;
    }
    let mut request = request_from_receipt(current);
    request.decoder_token = witness.decoder_token as i64;
    request.reader_generation = witness.reader_generation as i64;
    request.pair_generation = witness.pair_generation as i64;
    request.import_generation = witness.import_generation as i64;
    request.acquisition_time_ns = monotonic_now_ns();
    // Decoder/reader identities are not Manifold producer authority.  Words 14/15 remain
    // zero unless a future qualified host binding supplies that separate provenance.
    let effective = SourceReceipt::new(
        request,
        RESULT_EFFECTIVE,
        REASON_NONE,
        PEER_EFFECTIVE_STAGES,
    );
    owner.receipt = Some(effective);
    effective
}

pub(crate) fn record_peer_decoder_bound(
    route_generation: i64,
    decoder_token: u64,
    reader_generation: u64,
) -> SourceReceipt {
    let mut owner = SOURCE_OWNER
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(current) = owner.receipt else {
        return SourceReceipt::unavailable(route_generation, REASON_RECEIPT_UNAVAILABLE);
    };
    if current.words[1] != route_generation
        || current.words[2] != SOURCE_PEER
        || current.words[11] != RESULT_PENDING
        || decoder_token == 0
        || reader_generation == 0
    {
        return SourceReceipt::unavailable(route_generation, REASON_RECEIPT_FOREIGN);
    }
    let mut request = request_from_receipt(current);
    request.decoder_token = decoder_token as i64;
    request.reader_generation = reader_generation as i64;
    let bound = SourceReceipt::new(
        request,
        RESULT_PENDING,
        REASON_NONE,
        current.words[10] | STAGE_DECODER | STAGE_READER,
    );
    owner.receipt = Some(bound);
    bound
}

pub(crate) fn record_peer_common_graph_attached(route_generation: i64) -> SourceReceipt {
    let mut owner = SOURCE_OWNER
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(current) = owner.receipt else {
        return SourceReceipt::unavailable(route_generation, REASON_RECEIPT_UNAVAILABLE);
    };
    if current.words[1] != route_generation
        || current.words[2] != SOURCE_PEER
        || current.words[11] != RESULT_PENDING
        || current.words[3] <= 0
        || current.words[4] <= 0
    {
        return SourceReceipt::unavailable(route_generation, REASON_RECEIPT_FOREIGN);
    }
    let attached = SourceReceipt::new(
        request_from_receipt(current),
        RESULT_PENDING,
        REASON_NONE,
        current.words[10] | STAGE_COMMON_GRAPH,
    );
    owner.receipt = Some(attached);
    attached
}

pub(crate) fn record_local_submission_retired(
    route_generation: i64,
    reader_generation: u64,
    import_generation: u64,
) -> SourceReceipt {
    let mut owner = SOURCE_OWNER
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(current) = owner.receipt else {
        return SourceReceipt::unavailable(route_generation, REASON_RECEIPT_UNAVAILABLE);
    };
    if current.words[1] != route_generation || current.words[2] != SOURCE_LOCAL {
        return SourceReceipt::unavailable(route_generation, REASON_RECEIPT_FOREIGN);
    }
    if reader_generation == 0 || import_generation == 0 {
        drop(owner);
        return mark_route_lost(route_generation, REASON_MALFORMED);
    }
    let mut request = request_from_receipt(current);
    request.reader_generation = reader_generation as i64;
    request.import_generation = import_generation as i64;
    request.acquisition_time_ns = monotonic_now_ns();
    let effective = SourceReceipt::new(
        request,
        RESULT_EFFECTIVE,
        REASON_NONE,
        LOCAL_EFFECTIVE_STAGES,
    );
    owner.receipt = Some(effective);
    effective
}

pub(crate) fn record_acquisition_stopped(route_generation: i64) -> SourceReceipt {
    let mut owner = SOURCE_OWNER
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(current) = owner.receipt else {
        return SourceReceipt::unavailable(route_generation, REASON_RECEIPT_UNAVAILABLE);
    };
    if current.words[1] != route_generation || current.words[2] != SOURCE_DISABLED {
        return SourceReceipt::unavailable(route_generation, REASON_RECEIPT_FOREIGN);
    }
    let mut request = request_from_receipt(current);
    request.decoder_token = 0;
    request.reader_generation = 0;
    request.pair_generation = 0;
    request.import_generation = 0;
    request.producer_session = 0;
    request.producer_epoch = 0;
    request.acquisition_time_ns = monotonic_now_ns();
    let inactive = SourceReceipt::new(request, RESULT_INACTIVE, REASON_NONE, STAGE_STOPPED);
    owner.receipt = Some(inactive);
    inactive
}

pub(crate) fn record_current_acquisition_stopped_if_pending() -> Option<SourceReceipt> {
    let route_generation = {
        let owner = SOURCE_OWNER
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        owner.receipt.and_then(|receipt| {
            (receipt.words[2] == SOURCE_DISABLED && receipt.words[11] == RESULT_PENDING)
                .then_some(receipt.words[1])
        })
    }?;
    Some(record_acquisition_stopped(route_generation))
}

pub(crate) fn record_current_local_submission_retired(
    reader_generation: u64,
    import_generation: u64,
) -> Option<SourceReceipt> {
    let route_generation = {
        let owner = SOURCE_OWNER
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        owner.receipt.and_then(|receipt| {
            (receipt.words[2] == SOURCE_LOCAL && receipt.words[11] == RESULT_PENDING)
                .then_some(receipt.words[1])
        })
    }?;
    Some(record_local_submission_retired(
        route_generation,
        reader_generation,
        import_generation,
    ))
}

pub(crate) fn mark_route_lost(route_generation: i64, reason: i64) -> SourceReceipt {
    let mut owner = SOURCE_OWNER
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(current) = owner.receipt else {
        return SourceReceipt::unavailable(route_generation, REASON_RECEIPT_UNAVAILABLE);
    };
    if current.words[1] != route_generation {
        return SourceReceipt::unavailable(route_generation, REASON_RECEIPT_FOREIGN);
    }
    let lost = SourceReceipt::new(
        request_from_receipt(current),
        RESULT_LOST,
        reason,
        current.words[10] & !STAGE_ACTIVE,
    );
    owner.receipt = Some(lost);
    lost
}

pub(crate) fn read_source(route_generation: i64) -> SourceReceipt {
    let owner = SOURCE_OWNER
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(receipt) = owner.receipt else {
        return SourceReceipt::unavailable(route_generation, REASON_RECEIPT_UNAVAILABLE);
    };
    if receipt.words[1] != route_generation {
        return SourceReceipt::unavailable(route_generation, REASON_RECEIPT_FOREIGN);
    }
    receipt
}

pub(crate) fn request_producer_cleanup(
    route_generation: i64,
    producer_session: i64,
    producer_epoch: i64,
) -> SourceReceipt {
    let owner = SOURCE_OWNER
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(current) = owner.receipt else {
        return SourceReceipt::unavailable(route_generation, REASON_RECEIPT_UNAVAILABLE);
    };
    if current.words[1] != route_generation || producer_session <= 0 || producer_epoch <= 0 {
        return cleanup_receipt(
            route_generation,
            producer_session,
            producer_epoch,
            RESULT_UNAVAILABLE,
            REASON_CLEANUP_FOREIGN,
            0,
        );
    }
    if current.words[14] <= 0 || current.words[15] <= 0 {
        return cleanup_receipt(
            route_generation,
            producer_session,
            producer_epoch,
            RESULT_UNAVAILABLE,
            REASON_CLEANUP_REQUIRED,
            0,
        );
    }
    if current.words[14] != producer_session || current.words[15] != producer_epoch {
        return cleanup_receipt(
            route_generation,
            producer_session,
            producer_epoch,
            RESULT_UNAVAILABLE,
            REASON_CLEANUP_FOREIGN,
            0,
        );
    }
    if owner.inactive_witness
        == Some(QualifiedProducerInactiveWitness {
            route_generation,
            producer_session,
            producer_epoch,
        })
    {
        return cleanup_receipt(
            route_generation,
            producer_session,
            producer_epoch,
            RESULT_INACTIVE,
            REASON_NONE,
            STAGE_PRODUCER_INACTIVE,
        );
    }
    // A future host/operator binding must install a signed/qualified exact session+epoch
    // inactive receipt here after stopping the separate Manifold broker.  JNI must not
    // synthesize that authority from decoder shutdown or absence of frames.
    cleanup_receipt(
        route_generation,
        producer_session,
        producer_epoch,
        RESULT_UNAVAILABLE,
        REASON_CLEANUP_REQUIRED,
        0,
    )
}

#[cfg(test)]
fn install_inactive_witness_for_test(route_generation: i64, session: i64, epoch: i64) {
    SOURCE_OWNER
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .inactive_witness = Some(QualifiedProducerInactiveWitness {
        route_generation,
        producer_session: session,
        producer_epoch: epoch,
    });
}

fn cleanup_receipt(
    route_generation: i64,
    session: i64,
    epoch: i64,
    result: i64,
    reason: i64,
    stages: i64,
) -> SourceReceipt {
    let mut request = SourceRequest {
        route_generation,
        source: SOURCE_PEER,
        decoder_token: 0,
        reader_generation: 0,
        launch_challenge: 0,
        surface_generation: 0,
        pair_generation: 0,
        import_generation: 0,
        acquisition_time_ns: monotonic_now_ns(),
        required_stages: stages,
        camera_start_allowed: false,
        producer_session: session,
        producer_epoch: epoch,
    };
    request.required_stages = stages;
    SourceReceipt::new(request, result, reason, stages)
}

fn request_from_receipt(receipt: SourceReceipt) -> SourceRequest {
    SourceRequest {
        route_generation: receipt.words[1],
        source: receipt.words[2],
        decoder_token: receipt.words[3],
        reader_generation: receipt.words[4],
        launch_challenge: receipt.words[5],
        surface_generation: receipt.words[6],
        pair_generation: receipt.words[7],
        import_generation: receipt.words[8],
        acquisition_time_ns: receipt.words[9],
        required_stages: receipt.words[10],
        camera_start_allowed: receipt.words[13] == 1,
        producer_session: receipt.words[14],
        producer_epoch: receipt.words[15],
    }
}

pub(crate) fn monotonic_now_ns() -> i64 {
    #[cfg(target_os = "android")]
    {
        const CLOCK_MONOTONIC: libc::c_int = 1;
        unsafe extern "C" {
            fn clock_gettime(clock_id: libc::c_int, time_spec: *mut libc::timespec) -> libc::c_int;
        }
        let mut now = libc::timespec {
            tv_sec: 0,
            tv_nsec: 0,
        };
        if unsafe { clock_gettime(CLOCK_MONOTONIC, &mut now) } != 0 {
            return 0;
        }
        return now
            .tv_sec
            .saturating_mul(1_000_000_000)
            .saturating_add(now.tv_nsec as i64);
    }
    #[cfg(not(target_os = "android"))]
    {
        use std::time::Instant;
        static TEST_MONOTONIC_EPOCH: LazyLock<Instant> = LazyLock::new(Instant::now);
        i64::try_from(TEST_MONOTONIC_EPOCH.elapsed().as_nanos())
            .unwrap_or(i64::MAX)
            .saturating_add(1)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    static TEST_LOCK: LazyLock<Mutex<()>> = LazyLock::new(|| Mutex::new(()));

    fn reset() -> std::sync::MutexGuard<'static, ()> {
        let guard = TEST_LOCK.lock().unwrap();
        *SOURCE_OWNER.lock().unwrap() = SourceOwnerState::default();
        guard
    }

    fn request(generation: i64, source: i64) -> [i64; SOURCE_ABI_WORDS] {
        let stages = match source {
            SOURCE_DISABLED => STAGE_STOPPED,
            SOURCE_LOCAL => LOCAL_EFFECTIVE_STAGES,
            SOURCE_PEER => PEER_EFFECTIVE_STAGES,
            _ => 0,
        };
        [
            1,
            generation,
            source,
            0,
            0,
            71,
            9,
            0,
            0,
            0,
            stages,
            0,
            0,
            i64::from(source == SOURCE_LOCAL),
            0,
            0,
        ]
    }

    #[test]
    fn abi_decode_encode_and_peer_before_camera_start() {
        let _guard = reset();
        let pending = request_source(request(101, SOURCE_PEER), true);
        assert_eq!(pending.words[0], 1);
        assert_eq!(pending.words[1], 101);
        assert_eq!(pending.words[2], SOURCE_PEER);
        assert_eq!(pending.words[10], STAGE_PROVIDER | STAGE_SURFACE);
        assert_eq!(pending.words[11], RESULT_PENDING);
        assert_eq!(pending.words[13], 0);
    }

    #[test]
    fn peer_becomes_effective_only_after_matching_retirement() {
        let _guard = reset();
        request_source(request(102, SOURCE_PEER), true);
        record_peer_decoder_bound(102, 4, 5);
        record_peer_common_graph_attached(102);
        let effective = record_peer_submission_retired(
            102,
            PeerFrameWitness {
                decoder_token: 4,
                reader_generation: 5,
                pair_generation: 6,
                import_generation: 7,
            },
        );
        assert_eq!(effective.words[10], PEER_EFFECTIVE_STAGES);
        assert_eq!(effective.words[11], RESULT_EFFECTIVE);
        assert!(effective.words[9] > 0);
        assert_eq!(effective.words[14..16], [0, 0]);
    }

    #[test]
    fn stale_or_lost_state_never_retains_effective() {
        let _guard = reset();
        request_source(request(103, SOURCE_PEER), true);
        record_peer_decoder_bound(103, 4, 5);
        record_peer_common_graph_attached(103);
        record_peer_submission_retired(
            103,
            PeerFrameWitness {
                decoder_token: 4,
                reader_generation: 5,
                pair_generation: 6,
                import_generation: 7,
            },
        );
        let lost = mark_route_lost(103, REASON_STALE);
        assert_eq!(lost.words[11], RESULT_LOST);
        assert_eq!(lost.words[12], REASON_STALE);
        assert_eq!(lost.words[10] & STAGE_ACTIVE, 0);
    }

    #[test]
    fn cleanup_requires_real_exact_inactive_witness() {
        let _guard = reset();
        request_source(request(104, SOURCE_PEER), true);
        record_peer_decoder_bound(104, 14, 15);
        record_peer_common_graph_attached(104);
        record_peer_submission_retired(
            104,
            PeerFrameWitness {
                decoder_token: 14,
                reader_generation: 15,
                pair_generation: 16,
                import_generation: 17,
            },
        );
        let denied = request_producer_cleanup(104, 34, 35);
        assert_eq!(denied.words[11], RESULT_UNAVAILABLE);
        assert_eq!(denied.words[12], REASON_CLEANUP_REQUIRED);
        assert_eq!(denied.words[10] & STAGE_PRODUCER_INACTIVE, 0);
        install_inactive_witness_for_test(104, 34, 35);
        let still_foreign = request_producer_cleanup(104, 34, 35);
        assert_eq!(still_foreign.words[11], RESULT_UNAVAILABLE);
        assert_eq!(still_foreign.words[10], 0);
    }

    #[test]
    fn malformed_request_fails_closed() {
        let _guard = reset();
        let mut malformed = request(106, SOURCE_PEER);
        malformed[13] = 1;
        let receipt = request_source(malformed, true);
        assert_eq!(receipt.words[11], RESULT_UNAVAILABLE);
        assert_eq!(receipt.words[12], REASON_MALFORMED);
    }

    #[test]
    fn exact_kotlin_abi_stage_words_match_for_all_sources() {
        let _guard = reset();
        assert_eq!(request(201, SOURCE_DISABLED)[10], 128);
        assert_eq!(request(202, SOURCE_LOCAL)[10], 109);
        assert_eq!(request(203, SOURCE_PEER)[10], 639);
        assert_eq!(request(202, SOURCE_LOCAL)[13], 1);
        assert_eq!(request(203, SOURCE_PEER)[13], 0);
    }

    #[test]
    fn hot_local_peer_disabled_local_requests_advance_without_stale_effectiveness() {
        let _guard = reset();
        let local = request_source(request(301, SOURCE_LOCAL), true);
        assert_eq!(local.words[11], RESULT_PENDING);
        let peer = request_source(request(302, SOURCE_PEER), true);
        assert_eq!(peer.words[2], SOURCE_PEER);
        assert_eq!(peer.words[11], RESULT_PENDING);
        let disabled = request_source(request(303, SOURCE_DISABLED), true);
        assert_eq!(disabled.words[2], SOURCE_DISABLED);
        assert_eq!(disabled.words[11], RESULT_PENDING);
        assert_eq!(disabled.words[10], 0);
        let disabled = record_acquisition_stopped(303);
        assert_eq!(disabled.words[11], RESULT_INACTIVE);
        assert_eq!(disabled.words[10], STAGE_STOPPED);
        let local_again = request_source(request(304, SOURCE_LOCAL), true);
        assert_eq!(local_again.words[2], SOURCE_LOCAL);
        assert_eq!(local_again.words[11], RESULT_PENDING);
        assert_eq!(local_again.words[10] & STAGE_ACTIVE, 0);
    }

    #[test]
    fn local_effective_requires_real_reader_and_import_retirement_evidence() {
        let _guard = reset();
        request_source(request(401, SOURCE_LOCAL), true);
        let malformed = record_local_submission_retired(401, 0, 8);
        assert_eq!(malformed.words[11], RESULT_LOST);
        assert_eq!(malformed.words[12], REASON_MALFORMED);

        request_source(request(402, SOURCE_LOCAL), true);
        let effective = record_local_submission_retired(402, 7, 8);
        assert_eq!(effective.words[10], 109);
        assert_eq!(effective.words[4], 7);
        assert_eq!(effective.words[8], 8);
        assert_eq!(effective.words[11], RESULT_EFFECTIVE);
    }
}
