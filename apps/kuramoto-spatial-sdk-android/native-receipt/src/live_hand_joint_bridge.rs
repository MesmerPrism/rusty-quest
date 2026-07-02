use std::ptr;
use std::sync::atomic::{AtomicI64, AtomicU32, Ordering};
use std::sync::{Mutex, OnceLock};

use jni::sys::{jboolean, jclass, jfloatArray, jlong, JNIEnv};

use crate::live_hand_joints::{
    clear_live_hand_spatial_viewer_world_registration, copy_last_live_hand_raw_scene_rows,
    copy_last_live_hand_rows, copy_last_live_hand_spatial_viewer_world_rows,
    store_live_hand_panel_basis, store_live_hand_spatial_viewer_world_basis, LiveHandJointInput,
    LiveHandOpenXrHandles, LIVE_HAND_ROW_COUNT,
};
use crate::{android_log_info, bool_token};

const START_RECEIVED: i64 = 1 << 0;
const START_HANDLES_COMPLETE: i64 = 1 << 1;
const START_INPUT_READY: i64 = 1 << 2;
const POLL_MODE_PANEL: i64 = 0;
const POLL_MODE_RAW_SCENE: i64 = 1;
const POLL_MODE_SPATIAL_VIEWER_WORLD: i64 = 2;
const OPENXR_PALM_JOINT: usize = 0;
const OPENXR_WRIST_JOINT: usize = 1;

static START_MASK_BITS: AtomicI64 = AtomicI64::new(0);
static POLL_MODE_BITS: AtomicI64 = AtomicI64::new(POLL_MODE_PANEL);
static POLL_READY_LOG_COUNT: AtomicU32 = AtomicU32::new(0);
static BASIS_UPDATE_LOG_COUNT: AtomicU32 = AtomicU32::new(0);
static BRIDGE_LIVE_HAND_INPUT: OnceLock<Mutex<Option<LiveHandJointInput>>> = OnceLock::new();

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialLiveHandJointBridge_nativeStartLiveHandJoints(
    _env: *mut JNIEnv,
    _class: jclass,
    openxr_instance_handle: jlong,
    openxr_session_handle: jlong,
    openxr_get_instance_proc_addr_handle: jlong,
) -> jlong {
    let handles = LiveHandOpenXrHandles {
        instance_handle: openxr_instance_handle,
        session_handle: openxr_session_handle,
        get_instance_proc_addr_handle: openxr_get_instance_proc_addr_handle,
    };
    let handles_complete = handles.instance_handle != 0
        && handles.session_handle != 0
        && handles.get_instance_proc_addr_handle != 0;
    let cache_ready = copy_last_live_hand_rows().is_some();
    let mut direct_input_ready = false;
    let mut mask = START_RECEIVED;
    clear_live_hand_spatial_viewer_world_registration();
    if handles_complete {
        mask |= START_HANDLES_COMPLETE | START_INPUT_READY;
        let input = LiveHandJointInput::new(handles);
        direct_input_ready = input.status().input_ready;
        if let Ok(mut slot) = bridge_live_hand_input().lock() {
            if let Some(mut previous) = slot.take() {
                unsafe { previous.destroy() };
            }
            *slot = Some(input);
        }
    }

    START_MASK_BITS.store(mask, Ordering::Relaxed);
    POLL_READY_LOG_COUNT.store(0, Ordering::Relaxed);
    android_log_info(
        "RQKuramotoSpatialNative",
        &format!(
            "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=live-hand-joint-bridge status=started renderPolicy=no-render liveHandJointBridgeMode=direct-openxr-input-with-cache-poll liveHandJointBridgeStartMask={} liveHandJointBridgeSecondTracker=true liveHandJointBridgeCacheReady={} liveHandJointBridgeDirectInputReady={} liveHandJointGpuInputPath=recorded-compatible-compact-joint-pose-gpu-skinning {}",
            mask,
            bool_token(cache_ready),
            bool_token(direct_input_ready),
            handles.marker_fields(),
        ),
    );
    mask
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialLiveHandJointBridge_nativeUpdateLiveHandPanelBasis(
    _env: *mut JNIEnv,
    _class: jclass,
    center_x: f32,
    center_y: f32,
    center_z: f32,
    right_x: f32,
    right_y: f32,
    right_z: f32,
    up_x: f32,
    up_y: f32,
    up_z: f32,
    target_distance_meters: f32,
    valid: jboolean,
) -> jlong {
    let valid = valid != 0;
    store_live_hand_panel_basis(
        [
            finite_or(center_x, 0.0),
            finite_or(center_y, 1.22),
            finite_or(center_z, -0.72),
        ],
        [
            finite_or(right_x, 1.0),
            finite_or(right_y, 0.0),
            finite_or(right_z, 0.0),
        ],
        [
            finite_or(up_x, 0.0),
            finite_or(up_y, 1.0),
            finite_or(up_z, 0.0),
        ],
        finite_or(target_distance_meters, 0.72),
        valid,
    );
    let poll_mode = if valid {
        POLL_MODE_PANEL
    } else {
        POLL_MODE_RAW_SCENE
    };
    POLL_MODE_BITS.store(poll_mode, Ordering::Relaxed);
    log_basis_update("viewer-relative-panel", valid, poll_mode);
    1 | ((valid as i64) << 1)
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialLiveHandJointBridge_nativeUpdateLiveHandSpatialViewerWorldBasis(
    _env: *mut JNIEnv,
    _class: jclass,
    center_x: f32,
    center_y: f32,
    center_z: f32,
    right_x: f32,
    right_y: f32,
    right_z: f32,
    up_x: f32,
    up_y: f32,
    up_z: f32,
    valid: jboolean,
) -> jlong {
    let valid = valid != 0;
    store_live_hand_spatial_viewer_world_basis(
        [
            finite_or(center_x, 0.0),
            finite_or(center_y, 0.0),
            finite_or(center_z, 0.0),
        ],
        [
            finite_or(right_x, 1.0),
            finite_or(right_y, 0.0),
            finite_or(right_z, 0.0),
        ],
        [
            finite_or(up_x, 0.0),
            finite_or(up_y, 1.0),
            finite_or(up_z, 0.0),
        ],
        valid,
    );
    let poll_mode = if valid {
        POLL_MODE_SPATIAL_VIEWER_WORLD
    } else {
        POLL_MODE_RAW_SCENE
    };
    POLL_MODE_BITS.store(poll_mode, Ordering::Relaxed);
    log_basis_update("spatial-viewer-world", valid, poll_mode);
    1 | ((valid as i64) << 1)
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialLiveHandJointBridge_nativePollLiveHandJointRows(
    env: *mut JNIEnv,
    _class: jclass,
) -> jfloatArray {
    if START_MASK_BITS.load(Ordering::Relaxed) == 0 {
        return ptr::null_mut();
    }
    if let Ok(mut slot) = bridge_live_hand_input().lock() {
        if let Some(input) = slot.as_mut() {
            input.update_rows();
        }
    }
    let poll_mode = POLL_MODE_BITS.load(Ordering::Relaxed);
    let panel_snapshot = copy_last_live_hand_rows();
    let raw_scene_snapshot = copy_last_live_hand_raw_scene_rows();
    let spatial_viewer_world_snapshot = copy_last_live_hand_spatial_viewer_world_rows();
    let (snapshot, source, coordinate_space) = match poll_mode {
        POLL_MODE_RAW_SCENE => (
            raw_scene_snapshot,
            "cached-raw-scene-live-hand-rows",
            "raw-openxr-local-floor-to-spatial-sdk-scene",
        ),
        POLL_MODE_SPATIAL_VIEWER_WORLD => (
            spatial_viewer_world_snapshot,
            "cached-spatial-viewer-world-live-hand-rows",
            "openxr-local-floor-to-spatial-sdk-viewer-world-registration",
        ),
        _ => (
            panel_snapshot,
            "cached-gpu-panel-live-hand-rows",
            "viewer-relative-openxr-to-spatial-sdk-panel-basis",
        ),
    };
    let Some(snapshot) = snapshot else {
        return ptr::null_mut();
    };
    let mut values = Vec::with_capacity(LIVE_HAND_ROW_COUNT * 12);
    for row in snapshot.rows {
        values.extend_from_slice(&row.position_radius);
        values.extend_from_slice(&row.status);
        values.extend_from_slice(&row.orientation_xyzw);
    }

    let env = match unsafe { jni::JNIEnv::from_raw(env) } {
        Ok(env) => env,
        Err(_) => return ptr::null_mut(),
    };
    let Ok(array) = env.new_float_array(values.len() as i32) else {
        return ptr::null_mut();
    };
    if env.set_float_array_region(&array, 0, &values).is_err() {
        return ptr::null_mut();
    }
    let ready_log_index = POLL_READY_LOG_COUNT.fetch_add(1, Ordering::Relaxed);
    if ready_log_index < 4 {
        android_log_info(
            "RQKuramotoSpatialNative",
            &format!(
                "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=live-hand-joint-bridge status=frame-ready renderPolicy=no-render liveHandJointBridgeMode=selectable-row-cache liveHandJointBridgeSource={} liveHandJointBridgeCoordinateSpace={} liveHandJointBridgePollMode={} liveHandJointBridgeRows={} liveHandJointBridgeFloats={} liveHandJointBridgeFrameReady=true liveHandActiveHandCount={} liveHandFrameIndex={} liveHandTimestampNs={} liveHandJointGpuInputPath=recorded-compatible-compact-joint-pose-gpu-skinning",
                source,
                coordinate_space,
                poll_mode_marker(poll_mode),
                LIVE_HAND_ROW_COUNT,
                values.len(),
                snapshot.active_hand_count,
                snapshot.frame_index,
                snapshot.timestamp_ns,
            ),
        );
        log_live_hand_bridge_coordinate_diagnostics(
            ready_log_index,
            panel_snapshot.as_ref(),
            raw_scene_snapshot.as_ref(),
            spatial_viewer_world_snapshot.as_ref(),
        );
    }
    array.into_raw()
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialLiveHandJointBridge_nativeStopLiveHandJoints(
    _env: *mut JNIEnv,
    _class: jclass,
) -> jlong {
    let previous = START_MASK_BITS.swap(0, Ordering::Relaxed);
    clear_live_hand_spatial_viewer_world_registration();
    if let Ok(mut slot) = bridge_live_hand_input().lock() {
        if let Some(mut input) = slot.take() {
            unsafe { input.destroy() };
        }
    }
    previous.signum()
}

fn bridge_live_hand_input() -> &'static Mutex<Option<LiveHandJointInput>> {
    BRIDGE_LIVE_HAND_INPUT.get_or_init(|| Mutex::new(None))
}

fn log_basis_update(mode: &str, valid: bool, poll_mode: i64) {
    if BASIS_UPDATE_LOG_COUNT.fetch_add(1, Ordering::Relaxed) >= 4 {
        return;
    }
    android_log_info(
        "RQKuramotoSpatialNative",
        &format!(
            "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=live-hand-joint-bridge status=basis-updated renderPolicy=no-render liveHandJointBridgeMode=selectable-row-cache liveHandJointBridgeSecondTracker=false requestedBasisMode={} requestedBasisValid={} liveHandJointBridgePollMode={}",
            mode,
            bool_token(valid),
            poll_mode_marker(poll_mode),
        ),
    );
}

fn poll_mode_marker(mode: i64) -> &'static str {
    match mode {
        POLL_MODE_RAW_SCENE => "raw-scene",
        POLL_MODE_SPATIAL_VIEWER_WORLD => "spatial-viewer-world",
        _ => "viewer-relative-panel",
    }
}

fn finite_or(value: f32, fallback: f32) -> f32 {
    if value.is_finite() {
        value
    } else {
        fallback
    }
}

fn log_live_hand_bridge_coordinate_diagnostics(
    log_index: u32,
    panel: Option<&crate::live_hand_joints::LiveHandRowsSnapshot>,
    raw_scene: Option<&crate::live_hand_joints::LiveHandRowsSnapshot>,
    spatial_viewer_world: Option<&crate::live_hand_joints::LiveHandRowsSnapshot>,
) {
    let panel_left_wrist = row_position(panel, 0, OPENXR_WRIST_JOINT);
    let panel_right_wrist = row_position(panel, 1, OPENXR_WRIST_JOINT);
    let spatial_left_wrist = row_position(spatial_viewer_world, 0, OPENXR_WRIST_JOINT);
    let spatial_right_wrist = row_position(spatial_viewer_world, 1, OPENXR_WRIST_JOINT);
    let raw_left_wrist = row_position(raw_scene, 0, OPENXR_WRIST_JOINT);
    let raw_right_wrist = row_position(raw_scene, 1, OPENXR_WRIST_JOINT);
    let panel_left_palm = row_position(panel, 0, OPENXR_PALM_JOINT);
    let panel_right_palm = row_position(panel, 1, OPENXR_PALM_JOINT);
    let spatial_left_palm = row_position(spatial_viewer_world, 0, OPENXR_PALM_JOINT);
    let spatial_right_palm = row_position(spatial_viewer_world, 1, OPENXR_PALM_JOINT);
    android_log_info(
        "RQKuramotoSpatialNative",
        &format!(
            "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=live-hand-joint-bridge status=coordinate-diagnostic-counts renderPolicy=no-render liveHandJointBridgeMode=selectable-row-cache liveHandJointBridgeDiagnosticIndex={} liveHandJointBridgePanelActiveHandCount={} liveHandJointBridgeRawSceneActiveHandCount={} liveHandJointBridgeSpatialViewerWorldActiveHandCount={}",
            log_index,
            active_hand_count_marker(panel),
            active_hand_count_marker(raw_scene),
            active_hand_count_marker(spatial_viewer_world),
        ),
    );
    android_log_info(
        "RQKuramotoSpatialNative",
        &format!(
            "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=live-hand-joint-bridge status=coordinate-diagnostic-wrists renderPolicy=no-render liveHandJointBridgeMode=selectable-row-cache liveHandJointBridgeDiagnosticIndex={} liveHandJointBridgePanelLeftWristMeters={} liveHandJointBridgePanelRightWristMeters={} liveHandJointBridgeRawSceneLeftWristMeters={} liveHandJointBridgeRawSceneRightWristMeters={} liveHandJointBridgeSpatialViewerWorldLeftWristMeters={} liveHandJointBridgeSpatialViewerWorldRightWristMeters={} liveHandJointBridgeSpatialMinusPanelLeftWristMeters={} liveHandJointBridgeSpatialMinusPanelRightWristMeters={}",
            log_index,
            position_marker(panel_left_wrist),
            position_marker(panel_right_wrist),
            position_marker(raw_left_wrist),
            position_marker(raw_right_wrist),
            position_marker(spatial_left_wrist),
            position_marker(spatial_right_wrist),
            delta_marker(spatial_left_wrist, panel_left_wrist),
            delta_marker(spatial_right_wrist, panel_right_wrist),
        ),
    );
    android_log_info(
        "RQKuramotoSpatialNative",
        &format!(
            "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=live-hand-joint-bridge status=coordinate-diagnostic-palms renderPolicy=no-render liveHandJointBridgeMode=selectable-row-cache liveHandJointBridgeDiagnosticIndex={} liveHandJointBridgePanelLeftPalmMeters={} liveHandJointBridgePanelRightPalmMeters={} liveHandJointBridgeSpatialViewerWorldLeftPalmMeters={} liveHandJointBridgeSpatialViewerWorldRightPalmMeters={} liveHandJointBridgeSpatialMinusPanelLeftPalmMeters={} liveHandJointBridgeSpatialMinusPanelRightPalmMeters={}",
            log_index,
            position_marker(panel_left_palm),
            position_marker(panel_right_palm),
            position_marker(spatial_left_palm),
            position_marker(spatial_right_palm),
            delta_marker(spatial_left_palm, panel_left_palm),
            delta_marker(spatial_right_palm, panel_right_palm),
        ),
    );
}

fn active_hand_count_marker(
    snapshot: Option<&crate::live_hand_joints::LiveHandRowsSnapshot>,
) -> String {
    snapshot
        .map(|snapshot| snapshot.active_hand_count.to_string())
        .unwrap_or_else(|| "unavailable".to_string())
}

fn row_position(
    snapshot: Option<&crate::live_hand_joints::LiveHandRowsSnapshot>,
    hand_index: usize,
    joint_index: usize,
) -> Option<[f32; 3]> {
    let snapshot = snapshot?;
    let row_index = hand_index
        .saturating_mul(crate::live_hand_joints::LIVE_HAND_JOINT_COUNT)
        .saturating_add(joint_index);
    let row = snapshot.rows.get(row_index)?;
    if row.status[0] < 0.5 {
        return None;
    }
    Some([
        row.position_radius[0],
        row.position_radius[1],
        row.position_radius[2],
    ])
}

fn position_marker(position: Option<[f32; 3]>) -> String {
    position
        .map(|position| format!("{:.3};{:.3};{:.3}", position[0], position[1], position[2]))
        .unwrap_or_else(|| "unavailable".to_string())
}

fn delta_marker(a: Option<[f32; 3]>, b: Option<[f32; 3]>) -> String {
    match (a, b) {
        (Some(a), Some(b)) => position_marker(Some([a[0] - b[0], a[1] - b[1], a[2] - b[2]])),
        _ => "unavailable".to_string(),
    }
}
