//! Bounded in-process qualification state for the debug host receipt consumer.
//!
//! The state contains only counters and public rendering facts. It is reset by an explicit
//! app-owned arm operation and never serializes source paths, media identities, or host data.

use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering},
    Mutex,
};

pub(crate) const FIELD_REVISION: i32 = 0;
pub(crate) const FIELD_DECODER_STARTED: i32 = 1;
pub(crate) const FIELD_ERROR_CODE: i32 = 2;
pub(crate) const FIELD_FIRST_DECODED_FRAME: i32 = 3;
pub(crate) const FIELD_LAST_DECODED_FRAME: i32 = 4;
pub(crate) const FIELD_LAST_IMPORT_SEQUENCE: i32 = 5;
pub(crate) const FIELD_FIRST_TIMESTAMP_NS: i32 = 6;
pub(crate) const FIELD_LAST_TIMESTAMP_NS: i32 = 7;
pub(crate) const FIELD_WIDTH: i32 = 8;
pub(crate) const FIELD_HEIGHT: i32 = 9;
pub(crate) const FIELD_MAX_IMAGES: i32 = 10;
pub(crate) const FIELD_FPS_CAP: i32 = 11;
pub(crate) const FIELD_FIRST_ADOPTED_FRAME: i32 = 12;
pub(crate) const FIELD_LAST_ADOPTED_FRAME: i32 = 13;
pub(crate) const FIELD_LAST_PRESENT_ORDINAL: i32 = 14;
pub(crate) const FIELD_DISTINCT_ADOPTED_FRAMES: i32 = 15;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct QualificationState {
    revision: u64,
    decoder_started: bool,
    error_code: i32,
    first_decoded_frame: u64,
    last_decoded_frame: u64,
    last_import_sequence: u64,
    first_timestamp_ns: i64,
    last_timestamp_ns: i64,
    width: i32,
    height: i32,
    max_images: i32,
    fps_cap: i32,
    first_adopted_frame: u64,
    last_adopted_frame: u64,
    last_present_ordinal: u64,
    distinct_adopted_frames: u64,
}

const EMPTY_STATE: QualificationState = QualificationState {
    revision: 0,
    decoder_started: false,
    error_code: 0,
    first_decoded_frame: 0,
    last_decoded_frame: 0,
    last_import_sequence: 0,
    first_timestamp_ns: 0,
    last_timestamp_ns: 0,
    width: 0,
    height: 0,
    max_images: 0,
    fps_cap: 0,
    first_adopted_frame: 0,
    last_adopted_frame: 0,
    last_present_ordinal: 0,
    distinct_adopted_frames: 0,
};

static QUALIFICATION_STATE: Mutex<QualificationState> = Mutex::new(EMPTY_STATE);
static QUALIFICATION_ENABLED: AtomicBool = AtomicBool::new(false);
static QUALIFICATION_EPOCH: AtomicU64 = AtomicU64::new(0);

pub(crate) fn reset() {
    QUALIFICATION_ENABLED.store(false, Ordering::Release);
    QUALIFICATION_EPOCH.fetch_add(1, Ordering::AcqRel);
    if let Ok(mut state) = QUALIFICATION_STATE.lock() {
        let revision = state.revision.saturating_add(1);
        *state = QualificationState {
            revision,
            ..EMPTY_STATE
        };
    }
    QUALIFICATION_ENABLED.store(true, Ordering::Release);
}

pub(crate) fn disable() {
    QUALIFICATION_ENABLED.store(false, Ordering::Release);
}

pub(crate) fn record_lifecycle(
    event_code: i32,
    result_code: i32,
    width: i32,
    height: i32,
    max_images: i32,
    fps_cap: i32,
) {
    if !QUALIFICATION_ENABLED.load(Ordering::Acquire) {
        return;
    }
    let epoch = QUALIFICATION_EPOCH.load(Ordering::Acquire);
    if let Ok(mut state) = QUALIFICATION_STATE.lock() {
        if !QUALIFICATION_ENABLED.load(Ordering::Acquire)
            || epoch != QUALIFICATION_EPOCH.load(Ordering::Acquire)
        {
            return;
        }
        match event_code {
            4 | 8 => state.error_code = if result_code == 0 { -1 } else { result_code },
            5 => {
                state.width = width;
                state.height = height;
                state.max_images = max_images;
                state.fps_cap = fps_cap;
            }
            _ => {}
        }
        state.revision = state.revision.saturating_add(1);
    }
}

pub(crate) fn record_decoded_frame(
    frame_index: u64,
    import_sequence: u64,
    timestamp_ns: i64,
    width: i32,
    height: i32,
    max_images: i32,
    fps_cap: i32,
) {
    if !QUALIFICATION_ENABLED.load(Ordering::Acquire) {
        return;
    }
    let epoch = QUALIFICATION_EPOCH.load(Ordering::Acquire);
    if frame_index == 0 || import_sequence == 0 || timestamp_ns <= 0 {
        return;
    }
    if let Ok(mut state) = QUALIFICATION_STATE.lock() {
        if !QUALIFICATION_ENABLED.load(Ordering::Acquire)
            || epoch != QUALIFICATION_EPOCH.load(Ordering::Acquire)
        {
            return;
        }
        // An acquired AImage is the first trustworthy proof that MediaCodec produced output.
        state.decoder_started = true;
        if state.first_decoded_frame == 0 {
            state.first_decoded_frame = frame_index;
            state.first_timestamp_ns = timestamp_ns;
        }
        state.last_decoded_frame = frame_index;
        state.last_import_sequence = import_sequence;
        state.last_timestamp_ns = timestamp_ns;
        state.width = width;
        state.height = height;
        state.max_images = max_images;
        state.fps_cap = fps_cap;
        state.revision = state.revision.saturating_add(1);
    }
}

/// Records adoption only after the owner render loop has completed its present/retirement step.
pub(crate) fn record_presented_frame(
    ready: bool,
    rendered: bool,
    frame_index: u64,
    timestamp_ns: i64,
    present_ordinal: u64,
) {
    #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
    let _ = crate::camera_hwb_projection_freshness_runtime::record_vulkan_wsi_present_returned(
        present_ordinal,
    );
    if !QUALIFICATION_ENABLED.load(Ordering::Acquire) {
        return;
    }
    let epoch = QUALIFICATION_EPOCH.load(Ordering::Acquire);
    if !ready || !rendered || frame_index == 0 || timestamp_ns <= 0 || present_ordinal == 0 {
        return;
    }
    if let Ok(mut state) = QUALIFICATION_STATE.lock() {
        if !QUALIFICATION_ENABLED.load(Ordering::Acquire)
            || epoch != QUALIFICATION_EPOCH.load(Ordering::Acquire)
        {
            return;
        }
        // A frame that predates the explicit arm/reset cannot qualify the current observation.
        if state.first_decoded_frame == 0
            || frame_index < state.first_decoded_frame
            || timestamp_ns < state.first_timestamp_ns
            || timestamp_ns > state.last_timestamp_ns
        {
            return;
        }
        if state.first_adopted_frame == 0 {
            state.first_adopted_frame = frame_index;
        }
        if state.last_adopted_frame != frame_index {
            state.distinct_adopted_frames = state.distinct_adopted_frames.saturating_add(1);
        }
        state.last_adopted_frame = frame_index;
        state.last_present_ordinal = present_ordinal;
        state.revision = state.revision.saturating_add(1);
    }
}

pub(crate) fn read_field(field: i32) -> i64 {
    let Ok(state) = QUALIFICATION_STATE.lock() else {
        return -1;
    };
    match field {
        FIELD_REVISION => saturating_i64(state.revision),
        FIELD_DECODER_STARTED => i64::from(state.decoder_started),
        FIELD_ERROR_CODE => i64::from(state.error_code),
        FIELD_FIRST_DECODED_FRAME => saturating_i64(state.first_decoded_frame),
        FIELD_LAST_DECODED_FRAME => saturating_i64(state.last_decoded_frame),
        FIELD_LAST_IMPORT_SEQUENCE => saturating_i64(state.last_import_sequence),
        FIELD_FIRST_TIMESTAMP_NS => state.first_timestamp_ns,
        FIELD_LAST_TIMESTAMP_NS => state.last_timestamp_ns,
        FIELD_WIDTH => i64::from(state.width),
        FIELD_HEIGHT => i64::from(state.height),
        FIELD_MAX_IMAGES => i64::from(state.max_images),
        FIELD_FPS_CAP => i64::from(state.fps_cap),
        FIELD_FIRST_ADOPTED_FRAME => saturating_i64(state.first_adopted_frame),
        FIELD_LAST_ADOPTED_FRAME => saturating_i64(state.last_adopted_frame),
        FIELD_LAST_PRESENT_ORDINAL => saturating_i64(state.last_present_ordinal),
        FIELD_DISTINCT_ADOPTED_FRAMES => saturating_i64(state.distinct_adopted_frames),
        _ => -1,
    }
}

fn saturating_i64(value: u64) -> i64 {
    value.min(i64::MAX as u64) as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    static TEST_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn reset_rejects_pre_arm_adoption_and_tracks_distinct_presented_frames() {
        let _guard = TEST_LOCK.lock().expect("qualification test lock");
        reset();
        record_presented_frame(true, true, 8, 900, 1);
        assert_eq!(read_field(FIELD_DISTINCT_ADOPTED_FRAMES), 0);

        record_lifecycle(2, 0, 1920, 960, 4, 30);
        assert_eq!(read_field(FIELD_DECODER_STARTED), 0);
        record_decoded_frame(9, 1, 1_000, 1920, 960, 4, 30);
        record_presented_frame(true, true, 100, 500, 2);
        assert_eq!(read_field(FIELD_DISTINCT_ADOPTED_FRAMES), 0);
        record_presented_frame(true, true, 9, 1_000, 2);
        record_presented_frame(true, true, 9, 1_000, 3);
        record_decoded_frame(10, 2, 34_333_333, 1920, 960, 4, 30);
        record_presented_frame(true, true, 10, 34_333_333, 4);

        assert_eq!(read_field(FIELD_DECODER_STARTED), 1);
        assert_eq!(read_field(FIELD_FIRST_DECODED_FRAME), 9);
        assert_eq!(read_field(FIELD_LAST_DECODED_FRAME), 10);
        assert_eq!(read_field(FIELD_FIRST_ADOPTED_FRAME), 9);
        assert_eq!(read_field(FIELD_LAST_ADOPTED_FRAME), 10);
        assert_eq!(read_field(FIELD_DISTINCT_ADOPTED_FRAMES), 2);
        assert_eq!(read_field(FIELD_LAST_PRESENT_ORDINAL), 4);
    }

    #[test]
    fn damage_and_error_inputs_fail_closed() {
        let _guard = TEST_LOCK.lock().expect("qualification test lock");
        reset();
        record_decoded_frame(0, 0, 0, 0, 0, 0, 0);
        record_lifecycle(4, 0, 0, 0, 0, 0);
        assert_eq!(read_field(FIELD_FIRST_DECODED_FRAME), 0);
        assert_eq!(read_field(FIELD_ERROR_CODE), -1);
        assert_eq!(read_field(99), -1);

        disable();
        record_decoded_frame(1, 1, 1, 320, 240, 2, 30);
        assert_eq!(read_field(FIELD_FIRST_DECODED_FRAME), 0);
    }
}
