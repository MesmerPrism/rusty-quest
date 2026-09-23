use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

#[cfg(target_os = "android")]
use std::ffi::c_void;
#[cfg(target_os = "android")]
use std::os::raw::c_int;

use crate::camera_hwb_freshness::{
    CameraProjectionCadenceAuthority, CameraProjectionFreshnessObservation,
    CameraProjectionFreshnessSample, CameraProjectionFreshnessTracker, CameraProjectionLaunchFence,
};
#[cfg(target_os = "android")]
use crate::camera_hwb_marker::log_camera_hwb_marker as log_marker;
#[cfg(target_os = "android")]
use crate::camera_hwb_probe::CameraHwbProbeMode;
#[cfg(target_os = "android")]
use crate::camera_hwb_probe::Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStartCameraHwbProjectionProbe;

#[cfg(not(target_os = "android"))]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum CameraHwbProbeMode {
    LumaChecker,
    RawColorProjection,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct CameraProjectionFrameIdentity {
    pub(crate) stream_generation: u64,
    pub(crate) frame_index: u64,
    pub(crate) hwb_import_sequence: u64,
    pub(crate) timestamp_ns: i64,
    pub(crate) hardware_buffer_id: u64,
}

static NEXT_CAMERA_PROJECTION_FRESHNESS_GENERATION: AtomicU64 = AtomicU64::new(1);
static CAMERA_PROJECTION_FRESHNESS_RUNTIME: Mutex<CameraProjectionFreshnessRuntime> =
    Mutex::new(CameraProjectionFreshnessRuntime::new());

struct CameraProjectionFreshnessRuntime {
    live_fence: Option<CameraProjectionLaunchFence>,
    session: Option<CameraProjectionFreshnessSession>,
}

impl CameraProjectionFreshnessRuntime {
    const fn new() -> Self {
        Self {
            live_fence: None,
            session: None,
        }
    }
}

struct CameraProjectionFreshnessSession {
    generation: u64,
    launch_fence: CameraProjectionLaunchFence,
    tracker: Option<CameraProjectionFreshnessTracker>,
    pending: Option<CameraProjectionFreshnessSample>,
    staging_rejection: Option<&'static str>,
}

fn begin_camera_projection_freshness_session(
    launch_fence: CameraProjectionLaunchFence,
) -> Result<u64, &'static str> {
    let mut runtime = CAMERA_PROJECTION_FRESHNESS_RUNTIME
        .lock()
        .map_err(|_| "camera-projection-freshness-runtime-lock-unavailable")?;
    if runtime.live_fence != Some(launch_fence) {
        return Err("raw-projection-live-layer-fence-mismatch");
    }
    let generation = NEXT_CAMERA_PROJECTION_FRESHNESS_GENERATION.fetch_add(1, Ordering::AcqRel);
    if generation == 0 {
        return Err("camera-projection-freshness-generation-exhausted");
    }
    runtime.session = Some(CameraProjectionFreshnessSession {
        generation,
        launch_fence,
        tracker: None,
        pending: None,
        staging_rejection: None,
    });
    Ok(generation)
}

#[cfg(target_os = "android")]
fn cancel_camera_projection_freshness_session(generation: u64) {
    if let Ok(mut runtime) = CAMERA_PROJECTION_FRESHNESS_RUNTIME.lock() {
        if runtime
            .session
            .as_ref()
            .is_some_and(|session| session.generation == generation)
        {
            runtime.session = None;
        }
    }
}

fn update_camera_projection_layer_fence(
    fence: CameraProjectionLaunchFence,
) -> Result<(), &'static str> {
    let mut runtime = CAMERA_PROJECTION_FRESHNESS_RUNTIME
        .lock()
        .map_err(|_| "camera-projection-freshness-runtime-lock-unavailable")?;
    CameraProjectionLaunchFence::validate_monotonic_update(runtime.live_fence, fence)?;
    runtime.live_fence = Some(fence);
    Ok(())
}

#[cfg(target_os = "android")]
fn observe_camera_projection_freshness(
    tracker: &mut CameraProjectionFreshnessTracker,
    sample: CameraProjectionFreshnessSample,
    observed_layer_fence: Result<CameraProjectionLaunchFence, &'static str>,
) -> CameraProjectionFreshnessObservation {
    let present_ordinal = sample.present_ordinal;
    let outcome = tracker.observe_with_live_fence(sample, observed_layer_fence);
    log_camera_projection_freshness_observation(outcome, present_ordinal)
}

#[cfg(target_os = "android")]
fn log_camera_projection_freshness_observation(
    outcome: CameraProjectionFreshnessObservation,
    present_ordinal: u64,
) -> CameraProjectionFreshnessObservation {
    match outcome {
        CameraProjectionFreshnessObservation::Issued(receipt) => log_marker(format!(
            "status=camera-projection-freshness-receipt runtimeCrash=false {}",
            receipt.marker_fields(),
        )),
        CameraProjectionFreshnessObservation::Rejected(reason)
            if reason != "freshness-launch-already-rejected"
                || present_ordinal <= 4
                || present_ordinal % 300 == 0 =>
        {
            log_marker(format!(
                "status=camera-projection-freshness-rejected reason={} failClosed=true presentOrdinal={} runtimeCrash=false",
                reason, present_ordinal,
            ));
        }
        CameraProjectionFreshnessObservation::Primed
        | CameraProjectionFreshnessObservation::Pending
        | CameraProjectionFreshnessObservation::Rejected(_) => {}
    }
    outcome
}

#[cfg(not(target_os = "android"))]
fn log_camera_projection_freshness_observation(
    outcome: CameraProjectionFreshnessObservation,
    _present_ordinal: u64,
) -> CameraProjectionFreshnessObservation {
    outcome
}

fn reject_active_raw_staging(
    session: &mut CameraProjectionFreshnessSession,
    present_ordinal: u64,
    reason: &'static str,
) -> CameraProjectionFreshnessObservation {
    session.pending = None;
    let outcome = if session.staging_rejection.is_some() {
        CameraProjectionFreshnessObservation::Rejected("freshness-launch-already-rejected")
    } else {
        session.staging_rejection = Some(reason);
        match session.tracker.as_mut() {
            Some(tracker) => tracker.observe_live_fence(Err(reason)),
            None => CameraProjectionFreshnessObservation::Rejected(reason),
        }
    };
    log_camera_projection_freshness_observation(outcome, present_ordinal)
}

pub(crate) fn stage_camera_projection_command_buffer(
    present_ordinal: u64,
    probe_mode: CameraHwbProbeMode,
    camera_projection_visible: bool,
    left_frame_index: u64,
    right_frame_index: u64,
    left_timestamp_ns: i64,
    right_timestamp_ns: i64,
    frame_identities: Result<[CameraProjectionFrameIdentity; 2], &'static str>,
) -> Option<CameraProjectionFreshnessObservation> {
    let Ok(mut runtime) = CAMERA_PROJECTION_FRESHNESS_RUNTIME.lock() else {
        return None;
    };
    let Some(session) = runtime.session.as_mut() else {
        return None;
    };
    if !matches!(probe_mode, CameraHwbProbeMode::RawColorProjection) {
        let Ok([left, right]) = frame_identities else {
            return None;
        };
        if left.stream_generation == 0 || left.stream_generation != right.stream_generation {
            return None;
        }
        if left.frame_index != left_frame_index
            || right.frame_index != right_frame_index
            || left.timestamp_ns != left_timestamp_ns
            || right.timestamp_ns != right_timestamp_ns
        {
            return None;
        }
        return stage_camera_projection_sample(
            session,
            present_ordinal,
            probe_mode,
            camera_projection_visible,
            left_frame_index,
            right_frame_index,
            left_timestamp_ns,
            right_timestamp_ns,
            left,
            right,
        );
    }
    if session.staging_rejection.is_some() {
        return Some(reject_active_raw_staging(
            session,
            present_ordinal,
            "freshness-launch-already-rejected",
        ));
    }
    if session.pending.is_some() {
        return Some(reject_active_raw_staging(
            session,
            present_ordinal,
            "pending-camera-projection-command-buffer-replaced",
        ));
    }
    let [left, right] = match frame_identities {
        Ok(identities) => identities,
        Err(reason) => {
            return Some(reject_active_raw_staging(session, present_ordinal, reason));
        }
    };
    if left.stream_generation == 0 || left.stream_generation != right.stream_generation {
        return Some(reject_active_raw_staging(
            session,
            present_ordinal,
            "camera-hwb-wsi-import-generation-mismatch",
        ));
    }
    if left.frame_index != left_frame_index
        || right.frame_index != right_frame_index
        || left.timestamp_ns != left_timestamp_ns
        || right.timestamp_ns != right_timestamp_ns
    {
        return Some(reject_active_raw_staging(
            session,
            present_ordinal,
            "camera-hwb-wsi-import-frame-identity-mismatch",
        ));
    }
    stage_camera_projection_sample(
        session,
        present_ordinal,
        probe_mode,
        camera_projection_visible,
        left_frame_index,
        right_frame_index,
        left_timestamp_ns,
        right_timestamp_ns,
        left,
        right,
    )
}

#[allow(clippy::too_many_arguments)]
fn stage_camera_projection_sample(
    session: &mut CameraProjectionFreshnessSession,
    present_ordinal: u64,
    probe_mode: CameraHwbProbeMode,
    camera_projection_visible: bool,
    left_frame_index: u64,
    right_frame_index: u64,
    left_timestamp_ns: i64,
    right_timestamp_ns: i64,
    left: CameraProjectionFrameIdentity,
    right: CameraProjectionFrameIdentity,
) -> Option<CameraProjectionFreshnessObservation> {
    let launch_fence = session.launch_fence;
    if session.tracker.is_none() {
        session.tracker = Some(CameraProjectionFreshnessTracker::new(
            launch_fence,
            left.stream_generation,
            session.generation,
            CameraProjectionCadenceAuthority::VulkanWsiPresentReturned,
        ));
    }
    session.pending = Some(CameraProjectionFreshnessSample {
        launch_challenge: launch_fence.launch_challenge,
        layer_generation: launch_fence.layer_generation,
        layer_switch_count: launch_fence.layer_switch_count,
        layer_state: launch_fence.layer_state,
        run_generation: left.stream_generation,
        session_generation: session.generation,
        cadence_authority: CameraProjectionCadenceAuthority::VulkanWsiPresentReturned,
        cadence_available: true,
        cadence_ordinal: present_ordinal,
        present_ordinal,
        raw_projection_selected: matches!(probe_mode, CameraHwbProbeMode::RawColorProjection),
        camera_projection_visible,
        left_frame_index,
        right_frame_index,
        left_timestamp_ns,
        right_timestamp_ns,
        left_hwb_import_sequence: left.hwb_import_sequence,
        right_hwb_import_sequence: right.hwb_import_sequence,
        left_hardware_buffer_id: left.hardware_buffer_id,
        right_hardware_buffer_id: right.hardware_buffer_id,
    });
    None
}

pub(crate) fn record_vulkan_wsi_present_returned(
    present_ordinal: u64,
) -> Option<CameraProjectionFreshnessObservation> {
    let Ok(mut runtime) = CAMERA_PROJECTION_FRESHNESS_RUNTIME.lock() else {
        return None;
    };
    let live_fence = runtime.live_fence.ok_or("live-layer-fence-unavailable");
    let Some(session) = runtime.session.as_mut() else {
        return None;
    };
    if session.staging_rejection.is_some() {
        session.pending = None;
        return Some(log_camera_projection_freshness_observation(
            CameraProjectionFreshnessObservation::Rejected("freshness-launch-already-rejected"),
            present_ordinal,
        ));
    }
    let Some(sample) = session.pending.take() else {
        return None;
    };
    let Some(tracker) = session.tracker.as_mut() else {
        return None;
    };
    if sample.present_ordinal != present_ordinal {
        return Some(tracker.observe_live_fence(Err("presented-command-buffer-identity-mismatch")));
    }
    #[cfg(target_os = "android")]
    return Some(observe_camera_projection_freshness(
        tracker, sample, live_fence,
    ));
    #[cfg(not(target_os = "android"))]
    return Some(tracker.observe_with_live_fence(sample, live_fence));
}

#[cfg(target_os = "android")]
#[no_mangle]
#[allow(non_snake_case, clippy::too_many_arguments)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStartCameraHwbProjectionProbeWithFence(
    env: *mut c_void,
    thiz: *mut c_void,
    surface: *mut c_void,
    width: c_int,
    height: c_int,
    frame_count: c_int,
    reader_max_images: c_int,
    launch_challenge: i64,
    layer_generation: i64,
    layer_switch_count: i64,
    layer_state_code: c_int,
) -> i64 {
    let launch_fence = match CameraProjectionLaunchFence::from_raw(
        launch_challenge,
        layer_generation,
        layer_switch_count,
        layer_state_code,
    ) {
        Ok(fence) => fence,
        Err(reason) => {
            log_marker(format!(
                "status=start-receipt startStatus=raw-projection-launch-fence-rejected reason={} failClosed=true renderThreadSpawned=false runtimeCrash=false",
                reason,
            ));
            return 1;
        }
    };
    let generation = match begin_camera_projection_freshness_session(launch_fence) {
        Ok(generation) => generation,
        Err(reason) => {
            log_marker(format!(
                "status=start-receipt startStatus=raw-projection-live-layer-fence-unavailable reason={} failClosed=true renderThreadSpawned=false runtimeCrash=false",
                reason,
            ));
            return 1;
        }
    };
    let start_mask = Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStartCameraHwbProjectionProbe(
        env,
        thiz,
        surface,
        width,
        height,
        frame_count,
        reader_max_images,
    );
    if start_mask & (1 << 3) == 0 {
        cancel_camera_projection_freshness_session(generation);
    }
    start_mask
}

#[cfg(target_os = "android")]
#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdateCameraHwbProjectionLayerFence(
    _env: *mut c_void,
    _thiz: *mut c_void,
    launch_challenge: i64,
    layer_generation: i64,
    layer_switch_count: i64,
    layer_state_code: c_int,
) -> i64 {
    let Ok(fence) = CameraProjectionLaunchFence::observed_from_raw(
        launch_challenge,
        layer_generation,
        layer_switch_count,
        layer_state_code,
    ) else {
        return 0;
    };
    if update_camera_projection_layer_fence(fence).is_err() {
        return 0;
    }
    1
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fence(
        challenge: i64,
        generation: i64,
        switches: i64,
        state: i32,
    ) -> CameraProjectionLaunchFence {
        CameraProjectionLaunchFence::observed_from_raw(challenge, generation, switches, state)
            .expect("valid test fence")
    }

    #[test]
    fn runtime_requires_live_monotonic_fence_and_successful_present_identity() {
        let mut runtime = CAMERA_PROJECTION_FRESHNESS_RUNTIME.lock().unwrap();
        runtime.live_fence = None;
        runtime.session = None;
        drop(runtime);

        let active = fence(701, 1, 0, 1);
        assert!(begin_camera_projection_freshness_session(active).is_err());
        assert_eq!(
            stage_camera_projection_command_buffer(
                1,
                CameraHwbProbeMode::RawColorProjection,
                true,
                1,
                1,
                100,
                101,
                Err("left-camera-hwb-wsi-import-identity-unavailable"),
            ),
            None
        );
        update_camera_projection_layer_fence(active).unwrap();
        let generation = begin_camera_projection_freshness_session(active).unwrap();
        let identities = |stream_generation: u64, ordinal: u64| {
            Ok([
                CameraProjectionFrameIdentity {
                    stream_generation,
                    frame_index: ordinal,
                    hwb_import_sequence: ordinal,
                    timestamp_ns: ordinal as i64 * 100,
                    hardware_buffer_id: 11,
                },
                CameraProjectionFrameIdentity {
                    stream_generation,
                    frame_index: ordinal,
                    hwb_import_sequence: ordinal,
                    timestamp_ns: ordinal as i64 * 100 + 1,
                    hardware_buffer_id: 12,
                },
            ])
        };
        assert_eq!(
            stage_camera_projection_command_buffer(
                1,
                CameraHwbProbeMode::RawColorProjection,
                true,
                1,
                1,
                100,
                101,
                Err("left-camera-hwb-wsi-import-identity-unavailable"),
            ),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "left-camera-hwb-wsi-import-identity-unavailable"
            ))
        );
        assert_eq!(
            stage_camera_projection_command_buffer(
                1,
                CameraHwbProbeMode::RawColorProjection,
                true,
                1,
                1,
                100,
                101,
                identities(41, 1),
            ),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "freshness-launch-already-rejected"
            ))
        );
        assert_eq!(
            record_vulkan_wsi_present_returned(1),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "freshness-launch-already-rejected"
            ))
        );

        begin_camera_projection_freshness_session(active).unwrap();
        assert_eq!(
            stage_camera_projection_command_buffer(
                1,
                CameraHwbProbeMode::RawColorProjection,
                true,
                1,
                1,
                100,
                101,
                identities(0, 1),
            ),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "camera-hwb-wsi-import-generation-mismatch"
            ))
        );
        assert_eq!(
            stage_camera_projection_command_buffer(
                1,
                CameraHwbProbeMode::RawColorProjection,
                true,
                1,
                1,
                100,
                101,
                identities(41, 1),
            ),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "freshness-launch-already-rejected"
            ))
        );

        begin_camera_projection_freshness_session(active).unwrap();
        let mut split_generation = identities(41, 1).unwrap();
        split_generation[1].stream_generation = 42;
        assert_eq!(
            stage_camera_projection_command_buffer(
                1,
                CameraHwbProbeMode::RawColorProjection,
                true,
                1,
                1,
                100,
                101,
                Ok(split_generation),
            ),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "camera-hwb-wsi-import-generation-mismatch"
            ))
        );

        begin_camera_projection_freshness_session(active).unwrap();
        let mut timestamp_mismatch = identities(41, 1).unwrap();
        timestamp_mismatch[1].timestamp_ns = 999;
        assert_eq!(
            stage_camera_projection_command_buffer(
                1,
                CameraHwbProbeMode::RawColorProjection,
                true,
                1,
                1,
                100,
                101,
                Ok(timestamp_mismatch),
            ),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "camera-hwb-wsi-import-frame-identity-mismatch"
            ))
        );

        begin_camera_projection_freshness_session(active).unwrap();
        assert_eq!(
            stage_camera_projection_command_buffer(
                1,
                CameraHwbProbeMode::RawColorProjection,
                true,
                1,
                1,
                100,
                101,
                identities(41, 2),
            ),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "camera-hwb-wsi-import-frame-identity-mismatch"
            ))
        );
        assert_eq!(
            stage_camera_projection_command_buffer(
                1,
                CameraHwbProbeMode::RawColorProjection,
                true,
                1,
                1,
                100,
                101,
                identities(41, 1),
            ),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "freshness-launch-already-rejected"
            ))
        );

        begin_camera_projection_freshness_session(active).unwrap();
        assert_eq!(
            stage_camera_projection_command_buffer(
                1,
                CameraHwbProbeMode::RawColorProjection,
                true,
                1,
                1,
                100,
                101,
                identities(41, 1),
            ),
            None
        );
        assert_eq!(
            stage_camera_projection_command_buffer(
                2,
                CameraHwbProbeMode::RawColorProjection,
                true,
                2,
                2,
                200,
                201,
                identities(41, 2),
            ),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "pending-camera-projection-command-buffer-replaced"
            ))
        );
        assert_eq!(
            record_vulkan_wsi_present_returned(2),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "freshness-launch-already-rejected"
            ))
        );

        begin_camera_projection_freshness_session(active).unwrap();
        {
            let runtime = CAMERA_PROJECTION_FRESHNESS_RUNTIME.lock().unwrap();
            let session = runtime.session.as_ref().unwrap();
            assert!(session.generation > generation);
        }
        assert_eq!(
            stage_camera_projection_command_buffer(
                1,
                CameraHwbProbeMode::RawColorProjection,
                true,
                1,
                1,
                100,
                101,
                identities(41, 1),
            ),
            None
        );
        assert_eq!(
            record_vulkan_wsi_present_returned(1),
            Some(CameraProjectionFreshnessObservation::Primed)
        );
        assert_eq!(
            stage_camera_projection_command_buffer(
                2,
                CameraHwbProbeMode::RawColorProjection,
                true,
                2,
                2,
                200,
                201,
                identities(41, 3),
            ),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "camera-hwb-wsi-import-frame-identity-mismatch"
            ))
        );
        assert_eq!(
            stage_camera_projection_command_buffer(
                2,
                CameraHwbProbeMode::RawColorProjection,
                true,
                2,
                2,
                200,
                201,
                identities(41, 2),
            ),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "freshness-launch-already-rejected"
            ))
        );
        assert_eq!(
            record_vulkan_wsi_present_returned(2),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "freshness-launch-already-rejected"
            ))
        );

        begin_camera_projection_freshness_session(active).unwrap();
        assert_eq!(
            stage_camera_projection_command_buffer(
                1,
                CameraHwbProbeMode::RawColorProjection,
                true,
                1,
                1,
                100,
                101,
                identities(41, 1),
            ),
            None
        );
        assert_eq!(
            record_vulkan_wsi_present_returned(1),
            Some(CameraProjectionFreshnessObservation::Primed)
        );
        assert_eq!(
            stage_camera_projection_command_buffer(
                2,
                CameraHwbProbeMode::RawColorProjection,
                true,
                2,
                2,
                200,
                201,
                identities(41, 2),
            ),
            None
        );
        assert!(matches!(
            record_vulkan_wsi_present_returned(2),
            Some(CameraProjectionFreshnessObservation::Issued(_))
        ));

        assert_eq!(
            stage_camera_projection_command_buffer(
                3,
                CameraHwbProbeMode::RawColorProjection,
                true,
                3,
                3,
                300,
                301,
                identities(42, 3),
            ),
            None
        );
        assert_eq!(
            record_vulkan_wsi_present_returned(3),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "stale-run-generation"
            ))
        );

        let absent = fence(701, 1, 1, 0);
        update_camera_projection_layer_fence(absent).unwrap();
        assert_eq!(
            update_camera_projection_layer_fence(active),
            Err("live-layer-fence-transition-not-monotonic")
        );
        let replacement = fence(702, 1, 0, 1);
        update_camera_projection_layer_fence(replacement).unwrap();
        assert_eq!(
            update_camera_projection_layer_fence(active),
            Err("live-layer-fence-transition-not-monotonic")
        );
        begin_camera_projection_freshness_session(replacement).unwrap();
        assert_eq!(
            stage_camera_projection_command_buffer(
                1,
                CameraHwbProbeMode::RawColorProjection,
                true,
                1,
                1,
                100,
                101,
                identities(51, 1),
            ),
            None
        );
        assert_eq!(
            record_vulkan_wsi_present_returned(2),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "presented-command-buffer-identity-mismatch"
            ))
        );
        update_camera_projection_layer_fence(fence(702, 1, 1, 0)).unwrap();
        let second_replacement = fence(703, 1, 0, 1);
        update_camera_projection_layer_fence(second_replacement).unwrap();
        begin_camera_projection_freshness_session(second_replacement).unwrap();
        assert_eq!(
            stage_camera_projection_command_buffer(
                1,
                CameraHwbProbeMode::RawColorProjection,
                true,
                1,
                1,
                100,
                101,
                identities(61, 1),
            ),
            None
        );
        update_camera_projection_layer_fence(fence(703, 1, 1, 0)).unwrap();
        assert_eq!(
            record_vulkan_wsi_present_returned(1),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "live-layer-fence-mismatch"
            ))
        );
        let final_replacement = fence(704, 1, 0, 1);
        update_camera_projection_layer_fence(final_replacement).unwrap();
        begin_camera_projection_freshness_session(final_replacement).unwrap();
        assert_eq!(
            stage_camera_projection_command_buffer(
                1,
                CameraHwbProbeMode::LumaChecker,
                true,
                1,
                1,
                100,
                101,
                identities(71, 1),
            ),
            None
        );
        assert_eq!(
            record_vulkan_wsi_present_returned(1),
            Some(CameraProjectionFreshnessObservation::Rejected(
                "raw-projection-not-selected"
            ))
        );
    }
}
