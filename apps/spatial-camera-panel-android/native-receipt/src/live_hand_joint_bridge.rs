use std::ptr;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Mutex, OnceLock};

use jni::sys::{jboolean, jclass, jfloatArray, jlong, JNIEnv};

use crate::live_hand_joints::{
    store_live_hand_panel_basis, store_live_hand_spatial_viewer_basis, LiveHandJointInput,
    LiveHandOpenXrHandles, LIVE_HAND_ROW_COUNT,
};
use crate::{android_log_info, bool_token};

const START_RECEIVED: i64 = 1 << 0;
const START_HANDLES_COMPLETE: i64 = 1 << 1;
const START_INPUT_READY: i64 = 1 << 2;
const START_TRACKER_READY: i64 = 1 << 3;
const START_REFERENCE_SPACE_READY: i64 = 1 << 4;
const START_EXTENSION_FUNCTIONS_RESOLVED: i64 = 1 << 5;
const START_SYSTEM_SUPPORTED: i64 = 1 << 6;

struct LiveHandJointBridgeState {
    input: Option<LiveHandJointInput>,
}

unsafe impl Send for LiveHandJointBridgeState {}

static LIVE_HAND_JOINT_BRIDGE: OnceLock<Mutex<LiveHandJointBridgeState>> = OnceLock::new();
static POLL_READY_LOG_COUNT: AtomicU32 = AtomicU32::new(0);

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
    let mut input = LiveHandJointInput::new(handles);
    let status = input.status().clone();
    POLL_READY_LOG_COUNT.store(0, Ordering::Relaxed);
    let mut mask = START_RECEIVED;
    if handles.instance_handle != 0
        && handles.session_handle != 0
        && handles.get_instance_proc_addr_handle != 0
    {
        mask |= START_HANDLES_COMPLETE;
    }
    if status.input_ready {
        mask |= START_INPUT_READY;
    }
    if status.tracker_ready {
        mask |= START_TRACKER_READY;
    }
    if status.reference_space_ready {
        mask |= START_REFERENCE_SPACE_READY;
    }
    if status.extension_functions_resolved {
        mask |= START_EXTENSION_FUNCTIONS_RESOLVED;
    }
    if status.system_supported {
        mask |= START_SYSTEM_SUPPORTED;
    }

    let bridge =
        LIVE_HAND_JOINT_BRIDGE.get_or_init(|| Mutex::new(LiveHandJointBridgeState { input: None }));
    if let Ok(mut state) = bridge.lock() {
        if let Some(mut old_input) = state.input.take() {
            unsafe {
                old_input.destroy();
            }
        }
        state.input = Some(input);
    } else {
        unsafe {
            input.destroy();
        }
    }

    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=live-hand-joint-bridge status=started renderPolicy=no-render liveHandJointBridgeStartMask={} {} {}",
            mask,
            handles.marker_fields(),
            status.marker_fields(),
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
    let center = [
        finite_or(center_x, 0.0),
        finite_or(center_y, 1.22),
        finite_or(center_z, -0.72),
    ];
    let right = normalize_or(
        [
            finite_or(right_x, 1.0),
            finite_or(right_y, 0.0),
            finite_or(right_z, 0.0),
        ],
        [1.0, 0.0, 0.0],
    );
    let up = normalize_or(
        [
            finite_or(up_x, 0.0),
            finite_or(up_y, 1.0),
            finite_or(up_z, 0.0),
        ],
        [0.0, 1.0, 0.0],
    );
    let target_distance_meters = finite_or(target_distance_meters, 2.00).clamp(0.20, 2.00);
    let valid = valid != 0;
    store_live_hand_panel_basis(center, right, up, target_distance_meters, valid);
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
    let center = [
        finite_or(center_x, 0.0),
        finite_or(center_y, 1.22),
        finite_or(center_z, -0.72),
    ];
    let right = normalize_or(
        [
            finite_or(right_x, 1.0),
            finite_or(right_y, 0.0),
            finite_or(right_z, 0.0),
        ],
        [1.0, 0.0, 0.0],
    );
    let up = normalize_or(
        [
            finite_or(up_x, 0.0),
            finite_or(up_y, 1.0),
            finite_or(up_z, 0.0),
        ],
        [0.0, 1.0, 0.0],
    );
    let valid = valid != 0;
    store_live_hand_spatial_viewer_basis(center, right, up, valid);
    1 | ((valid as i64) << 1)
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialLiveHandJointBridge_nativePollLiveHandJointRows(
    env: *mut JNIEnv,
    _class: jclass,
) -> jfloatArray {
    let Some(bridge) = LIVE_HAND_JOINT_BRIDGE.get() else {
        return ptr::null_mut();
    };
    let mut state = match bridge.lock() {
        Ok(state) => state,
        Err(_) => return ptr::null_mut(),
    };
    let Some(input) = state.input.as_mut() else {
        return ptr::null_mut();
    };
    let rows = input.update_rows();
    let status = input.status().clone();
    let mut values = Vec::with_capacity(LIVE_HAND_ROW_COUNT * 12);
    for row in rows {
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
    if status.frame_ready && POLL_READY_LOG_COUNT.fetch_add(1, Ordering::Relaxed) < 4 {
        android_log_info(
            "RQSpatialCameraPanelNative",
            &format!(
                "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=live-hand-joint-bridge status=frame-ready renderPolicy=no-render liveHandJointBridgeRows={} liveHandJointBridgeFloats={} liveHandJointBridgeFrameReady={} liveHandActiveHandCount={} liveHandLeftActive={} liveHandRightActive={}",
                LIVE_HAND_ROW_COUNT,
                values.len(),
                bool_token(status.frame_ready),
                status.active_hand_count,
                bool_token(status.left_active),
                bool_token(status.right_active),
            ),
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
    let Some(bridge) = LIVE_HAND_JOINT_BRIDGE.get() else {
        return 0;
    };
    let Ok(mut state) = bridge.lock() else {
        return 0;
    };
    if let Some(mut input) = state.input.take() {
        unsafe {
            input.destroy();
        }
        return 1;
    }
    0
}

fn finite_or(value: f32, fallback: f32) -> f32 {
    if value.is_finite() {
        value
    } else {
        fallback
    }
}

fn normalize_or(value: [f32; 3], fallback: [f32; 3]) -> [f32; 3] {
    let len_sq = value[0] * value[0] + value[1] * value[1] + value[2] * value[2];
    if len_sq > 0.00000001 {
        let inv_len = len_sq.sqrt().recip();
        [value[0] * inv_len, value[1] * inv_len, value[2] * inv_len]
    } else {
        fallback
    }
}
