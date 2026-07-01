use std::ffi::{c_void, CStr, CString};
use std::mem;
#[cfg(target_os = "android")]
use std::os::raw::c_char;
use std::os::raw::{c_float, c_int};
use std::ptr;
use std::slice;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::thread;
use std::time::Instant;

use ash::vk;
use openxr_sys::Handle as OpenXrHandle;

#[cfg(target_os = "android")]
use crate::acamera_sys::{
    ANativeWindow as CameraNativeWindow, ANativeWindow_release as release_camera_native_window,
};
use crate::live_hand_joints::{store_live_hand_panel_basis, LiveHandOpenXrHandles};
use crate::replay_hands::{ReplayHandPanelProjection, ReplayHandsRenderer};
use crate::surface_particle_projection::{
    add3, canonical_world_basis, main_draw_panel_forward_distance, normalize_or,
    normalize_quat_or_none, panel_eye_position as projection_panel_eye_position,
    panel_forward as projection_panel_forward, rotate_vec3_by_quat, scale3,
    viewer_forward_roll_stable_camera_basis, viewer_forward_roll_stable_eye_position,
    viewer_forward_roll_stable_panel_center, viewer_sphere_center_distance_exceeds_threshold,
    SurfaceParticleFixedWorldRegistration, SurfaceParticleOpenXrPanelMapping,
};
use crate::{android_log_info, bool_token, marker_token};

const START_RECEIVED: i64 = 1 << 0;
const START_SURFACE_NON_NULL: i64 = 1 << 1;
const START_NATIVE_WINDOW_OBTAINED: i64 = 1 << 2;
const START_RENDER_THREAD_SPAWNED: i64 = 1 << 3;
const ALIAS_REQUEST_RECEIVED: i64 = 1 << 0;
const ALIAS_POLICY_PRESENT: i64 = 1 << 1;
const ALIAS_REQUEST_ACCEPTED: i64 = 1 << 2;
const ALIAS_REQUEST_REJECTED: i64 = 1 << 3;
const ALIAS_PUBLIC_FIELD_APPLIED: i64 = 1 << 4;
const ALIAS_DERIVED_FIELD_APPLIED: i64 = 1 << 5;
const RECENTER_REQUEST_RECEIVED: i64 = 1 << 0;
const RECENTER_VIEWER_POSE_VALID: i64 = 1 << 1;
const RECENTER_VIEWER_POSITION_FINITE: i64 = 1 << 2;
const RECENTER_WORLD_CENTER_STORED: i64 = 1 << 3;
const RECENTER_CANONICAL_BASIS_STORED: i64 = 1 << 4;
const RECENTER_WORLD_ANCHOR_VALID: i64 = 1 << 5;
const CLOCK_MONOTONIC: libc::c_int = 1;
const SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_MAPPED_LOG_LIMIT: u32 = 16;
const SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LOG_LIMIT: u32 = 32;
const SURFACE_PARTICLE_OFF_AXIS_PROJECTION_LOG_LIMIT: u32 = 16;
const SURFACE_PARTICLE_AUTO_RECENTER_LOG_LIMIT: u32 = 32;
const SURFACE_PARTICLE_AUTO_RECENTER_DISTANCE_METERS: f32 = 0.5;
const DEFAULT_SURFACE_PARTICLE_LEFT_EYE_OFFSET_RIGHT_METERS: f32 = -0.0315;
const DEFAULT_SURFACE_PARTICLE_RIGHT_EYE_OFFSET_RIGHT_METERS: f32 = 0.0315;
const DEFAULT_SURFACE_PARTICLE_SIM_WORLD_RADIUS_METERS: f32 = 2.0;

extern "C" {
    fn clock_gettime(clock_id: libc::c_int, time_spec: *mut libc::timespec) -> libc::c_int;
}

static STOP_SURFACE_PARTICLES: AtomicBool = AtomicBool::new(false);
static STOP_SDK_QUAD_VULKAN_PROBE: AtomicBool = AtomicBool::new(false);
static SURFACE_PARTICLE_PARAMETER_REVISION: AtomicU32 = AtomicU32::new(0);
static SURFACE_PARTICLE_DRIVER0_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_DRIVER1_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_DRIVER2_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_DRIVER3_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_DRIVER4_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_DRIVER5_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_DRIVER6_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_DRIVER7_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_POINT_SCALE_BITS: AtomicU32 = AtomicU32::new(0.70_f32.to_bits());
static SURFACE_PARTICLE_TRACER_DRAW_SLOTS_BITS: AtomicU32 = AtomicU32::new(7.0_f32.to_bits());
static SURFACE_PARTICLE_TRACER_LIFETIME_SECONDS_BITS: AtomicU32 = AtomicU32::new(0.5_f32.to_bits());
static SURFACE_PARTICLE_TRACER_COPIES_PER_SECOND_BITS: AtomicU32 =
    AtomicU32::new(14.0_f32.to_bits());
static SURFACE_PARTICLE_TRANSPARENCY_OPACITY_BITS: AtomicU32 = AtomicU32::new(0.36_f32.to_bits());
static SURFACE_PARTICLE_PROJECTION_WORLD_SCALE_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_LIVE_HAND_DEPTH_OFFSET_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_DIAGNOSTIC_MODE: AtomicU32 = AtomicU32::new(0);
static SURFACE_PARTICLE_HOTLOAD_LOG_COUNT: AtomicU32 = AtomicU32::new(0);
static SURFACE_PARTICLE_PANEL_CENTER_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_CENTER_Y_BITS: AtomicU32 = AtomicU32::new(1.22_f32.to_bits());
static SURFACE_PARTICLE_PANEL_CENTER_Z_BITS: AtomicU32 = AtomicU32::new((-2.0_f32).to_bits());
static SURFACE_PARTICLE_PANEL_RIGHT_X_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_RIGHT_Y_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_RIGHT_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_UP_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_UP_Y_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_UP_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_WIDTH_BITS: AtomicU32 = AtomicU32::new(4.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_HEIGHT_BITS: AtomicU32 = AtomicU32::new(4.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_TARGET_DISTANCE_BITS: AtomicU32 = AtomicU32::new(2.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_LEFT_EYE_OFFSET_RIGHT_BITS: AtomicU32 =
    AtomicU32::new(DEFAULT_SURFACE_PARTICLE_LEFT_EYE_OFFSET_RIGHT_METERS.to_bits());
static SURFACE_PARTICLE_PANEL_RIGHT_EYE_OFFSET_RIGHT_BITS: AtomicU32 =
    AtomicU32::new(DEFAULT_SURFACE_PARTICLE_RIGHT_EYE_OFFSET_RIGHT_METERS.to_bits());
static SURFACE_PARTICLE_PANEL_POSE_VALID: AtomicBool = AtomicBool::new(false);
static SURFACE_PARTICLE_PANEL_POSE_LOG_COUNT: AtomicU32 = AtomicU32::new(0);
static SURFACE_PARTICLE_VIEWER_WORLD_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_VIEWER_WORLD_Y_BITS: AtomicU32 = AtomicU32::new(1.4_f32.to_bits());
static SURFACE_PARTICLE_VIEWER_WORLD_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_VIEWER_RIGHT_X_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_VIEWER_RIGHT_Y_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_VIEWER_RIGHT_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_VIEWER_UP_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_VIEWER_UP_Y_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_VIEWER_UP_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_VIEWER_FORWARD_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_VIEWER_FORWARD_Y_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_VIEWER_FORWARD_Z_BITS: AtomicU32 = AtomicU32::new((-1.0_f32).to_bits());
static SURFACE_PARTICLE_LEFT_EYE_WORLD_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_LEFT_EYE_WORLD_Y_BITS: AtomicU32 = AtomicU32::new(1.4_f32.to_bits());
static SURFACE_PARTICLE_LEFT_EYE_WORLD_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_RIGHT_EYE_WORLD_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_RIGHT_EYE_WORLD_Y_BITS: AtomicU32 = AtomicU32::new(1.4_f32.to_bits());
static SURFACE_PARTICLE_RIGHT_EYE_WORLD_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_VIEWER_EYE_POSE_VALID: AtomicBool = AtomicBool::new(false);
static SURFACE_PARTICLE_VIEWER_EYE_POSE_LOG_COUNT: AtomicU32 = AtomicU32::new(0);
static SURFACE_PARTICLE_WORLD_ANCHOR_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_WORLD_ANCHOR_Y_BITS: AtomicU32 = AtomicU32::new(1.22_f32.to_bits());
static SURFACE_PARTICLE_WORLD_ANCHOR_Z_BITS: AtomicU32 = AtomicU32::new((-2.0_f32).to_bits());
static SURFACE_PARTICLE_WORLD_FORWARD_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_WORLD_FORWARD_Y_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_WORLD_FORWARD_Z_BITS: AtomicU32 = AtomicU32::new((-1.0_f32).to_bits());
static SURFACE_PARTICLE_WORLD_RIGHT_X_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_WORLD_RIGHT_Y_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_WORLD_RIGHT_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_WORLD_UP_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_WORLD_UP_Y_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_WORLD_UP_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_WORLD_ANCHOR_VALID: AtomicBool = AtomicBool::new(false);
static SURFACE_PARTICLE_WORLD_ANCHOR_SKIP_LOG_COUNT: AtomicU32 = AtomicU32::new(0);
static SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_RAW_X_BITS: AtomicU32 =
    AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_RAW_Y_BITS: AtomicU32 =
    AtomicU32::new(1.22_f32.to_bits());
static SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_RAW_Z_BITS: AtomicU32 =
    AtomicU32::new((-2.0_f32).to_bits());
static SURFACE_PARTICLE_OPENXR_WORLD_FORWARD_RAW_X_BITS: AtomicU32 =
    AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OPENXR_WORLD_FORWARD_RAW_Y_BITS: AtomicU32 =
    AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OPENXR_WORLD_FORWARD_RAW_Z_BITS: AtomicU32 =
    AtomicU32::new((-1.0_f32).to_bits());
static SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_MAPPED_X_BITS: AtomicU32 =
    AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_MAPPED_Y_BITS: AtomicU32 =
    AtomicU32::new(1.22_f32.to_bits());
static SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_MAPPED_Z_BITS: AtomicU32 =
    AtomicU32::new((-2.0_f32).to_bits());
static SURFACE_PARTICLE_OPENXR_WORLD_FORWARD_MAPPED_X_BITS: AtomicU32 =
    AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OPENXR_WORLD_FORWARD_MAPPED_Y_BITS: AtomicU32 =
    AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OPENXR_WORLD_FORWARD_MAPPED_Z_BITS: AtomicU32 =
    AtomicU32::new((-1.0_f32).to_bits());
static SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_VALID: AtomicBool = AtomicBool::new(false);
static SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_MAPPED_VALID: AtomicBool = AtomicBool::new(false);
static SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_LOG_COUNT: AtomicU32 = AtomicU32::new(0);
static SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_SKIP_LOG_COUNT: AtomicU32 = AtomicU32::new(0);
static SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LOG_COUNT: AtomicU32 = AtomicU32::new(0);
static SURFACE_PARTICLE_AUTO_RECENTER_LOG_COUNT: AtomicU32 = AtomicU32::new(0);
static SURFACE_PARTICLE_AUTO_RECENTER_LOCKED: AtomicBool = AtomicBool::new(false);
static SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_TARGET_DISTANCE_BITS: AtomicU32 =
    AtomicU32::new(0);
static SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_CENTER_X_BITS: AtomicU32 =
    AtomicU32::new(0);
static SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_CENTER_Y_BITS: AtomicU32 =
    AtomicU32::new(0);
static SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_CENTER_Z_BITS: AtomicU32 =
    AtomicU32::new(0);
static SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_FORWARD_X_BITS: AtomicU32 =
    AtomicU32::new(0);
static SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_FORWARD_Y_BITS: AtomicU32 =
    AtomicU32::new(0);
static SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_FORWARD_Z_BITS: AtomicU32 =
    AtomicU32::new(0);
static SURFACE_PARTICLE_OFF_AXIS_REGISTRATION_VALID: AtomicBool = AtomicBool::new(false);
static SURFACE_PARTICLE_OFF_AXIS_PROJECTION_VALID: AtomicBool = AtomicBool::new(false);
static SURFACE_PARTICLE_OFF_AXIS_REGISTRATION_LOG_COUNT: AtomicU32 = AtomicU32::new(0);
static SURFACE_PARTICLE_OFF_AXIS_PROJECTION_LOG_COUNT: AtomicU32 = AtomicU32::new(0);
static SURFACE_PARTICLE_OFF_AXIS_RAW_ORIGIN_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_RAW_ORIGIN_Y_BITS: AtomicU32 = AtomicU32::new(1.22_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_RAW_ORIGIN_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_RAW_RIGHT_X_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_RAW_RIGHT_Y_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_RAW_RIGHT_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_RAW_UP_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_RAW_UP_Y_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_RAW_UP_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_RAW_FORWARD_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_RAW_FORWARD_Y_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_RAW_FORWARD_Z_BITS: AtomicU32 =
    AtomicU32::new((-1.0_f32).to_bits());
static SURFACE_PARTICLE_OFF_AXIS_SCENE_ORIGIN_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_SCENE_ORIGIN_Y_BITS: AtomicU32 =
    AtomicU32::new(1.22_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_SCENE_ORIGIN_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_SCENE_RIGHT_X_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_SCENE_RIGHT_Y_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_SCENE_RIGHT_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_SCENE_UP_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_SCENE_UP_Y_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_SCENE_UP_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_SCENE_FORWARD_X_BITS: AtomicU32 =
    AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_SCENE_FORWARD_Y_BITS: AtomicU32 =
    AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_SCENE_FORWARD_Z_BITS: AtomicU32 =
    AtomicU32::new((-1.0_f32).to_bits());
static SURFACE_PARTICLE_OFF_AXIS_PANEL_CENTER_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_PANEL_CENTER_Y_BITS: AtomicU32 =
    AtomicU32::new(1.22_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_PANEL_CENTER_Z_BITS: AtomicU32 =
    AtomicU32::new((-2.0_f32).to_bits());
static SURFACE_PARTICLE_OFF_AXIS_PANEL_RIGHT_X_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_PANEL_RIGHT_Y_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_PANEL_RIGHT_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_PANEL_UP_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_PANEL_UP_Y_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_OFF_AXIS_PANEL_UP_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());

const SURFACE_PARTICLE_COMPUTE_LOCAL_SIZE: u32 = 64;
const PRIVATE_SURFACE_PARTICLE_DESCRIPTOR_SET_COUNT: usize = 2;
const PRIVATE_SURFACE_PARTICLE_VERTICES_PER_INSTANCE: u32 = 6;
const PRIVATE_SURFACE_PARTICLE_OUTPUT_ROWS_PER_INSTANCE: u64 = 4;
const PRIVATE_SURFACE_PARTICLE_MAIN_STATE_ROWS_PER_INSTANCE: u64 = 2;
const PRIVATE_SURFACE_PARTICLE_TRACER_STATE_ROWS_PER_SLOT: u64 = 4;
const PRIVATE_SURFACE_PARTICLE_DRIVER_BANK_SLOT_COUNT: u64 = 8;
const PRIVATE_SURFACE_PARTICLE_DRIVER_BANK_VALUE_VEC4_ROWS: u64 =
    PRIVATE_SURFACE_PARTICLE_DRIVER_BANK_SLOT_COUNT / 4;
const PRIVATE_SURFACE_PARTICLE_DRIVER_CONTROL_ROWS_PER_SLOT: u64 = 3;
const PRIVATE_SURFACE_PARTICLE_DRIVER_BANK_VEC4_ROWS: u64 =
    PRIVATE_SURFACE_PARTICLE_DRIVER_BANK_VALUE_VEC4_ROWS
        + PRIVATE_SURFACE_PARTICLE_DRIVER_BANK_SLOT_COUNT
            * PRIVATE_SURFACE_PARTICLE_DRIVER_CONTROL_ROWS_PER_SLOT;
const PRIVATE_SURFACE_PARTICLE_DRIVER_MODE_DIRECT: f32 = 3.0;
const PRIVATE_SURFACE_PARTICLE_DIAGNOSTIC_WORDS: u64 = 24;
const SURFACE_PARTICLE_LIVE_HAND_DEPTH_OFFSET_PROPERTY: &str =
    "debug.rustyquest.spatial_camera_panel.live_hand_depth_offset_meters";
const SURFACE_PARTICLE_DIAGNOSTIC_MODE_PROPERTY: &str =
    "debug.rustyquest.spatial_camera_panel.particle_layer.diagnostic_mode";
const SURFACE_PARTICLE_RENDERER_MODE_PROPERTY: &str =
    "debug.rustyquest.spatial_camera_panel.particle_layer.renderer_mode";
const SURFACE_PARTICLE_PROJECTION_MARKERS: &str =
    "projectionContentMappingMode=spatial-world-to-panel-plane-left-right targetCoordinateSpace=spatial-sdk-surface-panel-eye-uv targetProjectionSpace=spatial-sdk-panel-plane-perspective-projection targetFovTangents=spatial-sdk-panel-plane leftTargetSurfaceUvRect=0.0;0.0;1.0;1.0 rightTargetSurfaceUvRect=0.0;0.0;1.0;1.0 targetClipPolicy=clip-to-panel-eye-surface rendererSurfaceUvOrigin=native-vulkan-dynamic-viewport-scissor worldToPanelProjection=spatial-sdk-panel-plane-basis carrierSurfaceProjection=spatial-sdk-panel-plane-basis";

mod surface_particle_build_metadata {
    include!(concat!(
        env!("OUT_DIR"),
        "/spatial_public_multistack_build.rs"
    ));
}

mod surface_particle_private_payload {
    include!(concat!(
        env!("OUT_DIR"),
        "/spatial_surface_private_particle_payload.rs"
    ));
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum SurfaceParticleRendererMode {
    PublicHandAnchorProof,
    PrivateMetadataOnly,
    PrivateMainDrawOnly,
}

impl SurfaceParticleRendererMode {
    fn marker_fields(self) -> &'static str {
        match self {
            Self::PublicHandAnchorProof => {
                "surfaceParticleRendererMode=public-hand-anchor-proof privateSurfaceParticleRendererMode=placeholder-unavailable privateSurfaceParticleRendererSelection=public-default privateSurfaceParticleMetadataActive=false privateSurfaceParticlePayloadActive=false privateSurfaceParticleExecutionReady=false"
            }
            Self::PrivateMetadataOnly => {
                "surfaceParticleRendererMode=public-hand-anchor-proof privateSurfaceParticleRendererMode=metadata-only privateSurfaceParticleRendererSelection=private-metadata-only-public-fallback privateSurfaceParticleMetadataActive=true privateSurfaceParticlePayloadActive=false privateSurfaceParticleExecutionReady=false"
            }
            Self::PrivateMainDrawOnly => {
                "surfaceParticleRendererMode=private-surface-particle-main-draw privateSurfaceParticleRendererMode=main-draw-only privateSurfaceParticleRendererSelection=private-main-draw-only-no-public-fallback privateSurfaceParticleMetadataActive=true privateSurfaceParticlePayloadActive=true privateSurfaceParticleMainComputeOnly=false privateSurfaceParticleMainDrawOnly=true privateSurfaceParticleVisible=true privateSurfaceParticleTracersActive=true privateSurfaceParticleExecutionReady=true privateSurfaceParticlePublicFallbackActive=false"
            }
        }
    }

    fn compact_marker_fields(self) -> &'static str {
        match self {
            Self::PublicHandAnchorProof => {
                "surfaceParticleRendererMode=public-hand-anchor-proof privateSurfaceParticleRendererMode=placeholder-unavailable privateSurfaceParticleVisible=false privateSurfaceParticleTracersActive=false privateSurfaceParticleExecutionReady=false"
            }
            Self::PrivateMetadataOnly => {
                "surfaceParticleRendererMode=public-hand-anchor-proof privateSurfaceParticleRendererMode=metadata-only privateSurfaceParticleVisible=false privateSurfaceParticleTracersActive=false privateSurfaceParticleExecutionReady=false"
            }
            Self::PrivateMainDrawOnly => {
                "surfaceParticleRendererMode=private-surface-particle-main-draw privateSurfaceParticleRendererMode=main-draw-only privateSurfaceParticleVisible=true privateSurfaceParticleTracersActive=true privateSurfaceParticleExecutionReady=true privateSurfaceParticlePublicFallbackActive=false"
            }
        }
    }
}

fn current_surface_particle_renderer_mode() -> SurfaceParticleRendererMode {
    let build_config = SurfaceParticlePrivateBuildConfig::current();
    selected_surface_particle_renderer_mode(build_config)
}

fn selected_surface_particle_renderer_mode(
    build_config: SurfaceParticlePrivateBuildConfig,
) -> SurfaceParticleRendererMode {
    let requested_mode = android_system_property(SURFACE_PARTICLE_RENDERER_MODE_PROPERTY)
        .as_deref()
        .and_then(parse_surface_particle_renderer_mode);
    match requested_mode {
        Some(SurfaceParticleRendererMode::PublicHandAnchorProof) => {
            SurfaceParticleRendererMode::PublicHandAnchorProof
        }
        Some(SurfaceParticleRendererMode::PrivateMetadataOnly) => {
            if build_config.any_configured() {
                SurfaceParticleRendererMode::PrivateMetadataOnly
            } else {
                SurfaceParticleRendererMode::PublicHandAnchorProof
            }
        }
        Some(SurfaceParticleRendererMode::PrivateMainDrawOnly) => {
            if build_config.staged_payload_ready {
                SurfaceParticleRendererMode::PrivateMainDrawOnly
            } else if build_config.any_configured() {
                SurfaceParticleRendererMode::PrivateMetadataOnly
            } else {
                SurfaceParticleRendererMode::PublicHandAnchorProof
            }
        }
        None => {
            if !build_config.any_configured() {
                SurfaceParticleRendererMode::PublicHandAnchorProof
            } else if build_config.staged_payload_ready {
                SurfaceParticleRendererMode::PrivateMainDrawOnly
            } else {
                SurfaceParticleRendererMode::PrivateMetadataOnly
            }
        }
    }
}

fn parse_surface_particle_renderer_mode(value: &str) -> Option<SurfaceParticleRendererMode> {
    let normalized = value.trim().to_ascii_lowercase();
    match normalized.as_str() {
        "" | "auto" | "default" | "build-default" => None,
        "0"
        | "public"
        | "public-hand-anchor"
        | "public-hand-anchor-proof"
        | "live-hand-anchor"
        | "live-hand-anchor-proof" => Some(SurfaceParticleRendererMode::PublicHandAnchorProof),
        "1" | "metadata" | "private-metadata" | "private-metadata-only" => {
            Some(SurfaceParticleRendererMode::PrivateMetadataOnly)
        }
        "2" | "private" | "private-main" | "private-main-draw" | "private-main-draw-only" => {
            Some(SurfaceParticleRendererMode::PrivateMainDrawOnly)
        }
        _ => None,
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct SurfaceParticlePrivateBuildConfig {
    profile_configured: bool,
    shader_configured: bool,
    payload_dir_configured: bool,
    marker_prefix_configured: bool,
    shader_compiled: bool,
    shader_byte_count: usize,
    payload_files_present: bool,
    positions_byte_count: usize,
    normals_byte_count: usize,
    aux0_byte_count: usize,
    mask_texture_byte_count: usize,
    staged_payload_ready: bool,
    profile_counts_present: bool,
    profile_id_hash: u64,
    main_particle_count: usize,
    tracer_state_capacity: usize,
    tracer_draw_count: usize,
    retention_indicator_draw_count: usize,
    default_draw_count: usize,
    alias_policy_present: bool,
    public_runtime_packet_field_count: usize,
    active_alias_count: usize,
    activation_gated_alias_count: usize,
    future_rejection_marker_count: usize,
    forbidden_alias_payload_count: usize,
}

#[derive(Clone, Copy, Debug)]
struct SurfaceParticleWorldAnchor {
    center: [f32; 3],
    right: [f32; 3],
    up: [f32; 3],
    forward: [f32; 3],
    valid: bool,
}

impl SurfaceParticlePrivateBuildConfig {
    fn current() -> Self {
        Self {
            profile_configured:
                surface_particle_build_metadata::PRIVATE_SURFACE_PARTICLE_PROFILE_CONFIGURED,
            shader_configured:
                surface_particle_build_metadata::PRIVATE_SURFACE_PARTICLE_SHADER_CONFIGURED,
            payload_dir_configured:
                surface_particle_build_metadata::PRIVATE_SURFACE_PARTICLE_PAYLOAD_DIR_CONFIGURED,
            marker_prefix_configured:
                surface_particle_build_metadata::PRIVATE_SURFACE_PARTICLE_MARKER_PREFIX_CONFIGURED,
            shader_compiled:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_SHADER_COMPILED,
            shader_byte_count:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_SHADER_BYTE_COUNT,
            payload_files_present:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_PAYLOAD_FILES_PRESENT,
            positions_byte_count:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_POSITIONS_BYTE_COUNT,
            normals_byte_count:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_NORMALS_BYTE_COUNT,
            aux0_byte_count:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_AUX0_BYTE_COUNT,
            mask_texture_byte_count:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_MASK_TEXTURE_BYTE_COUNT,
            staged_payload_ready:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_STAGED_PAYLOAD_READY,
            profile_counts_present:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_PROFILE_COUNTS_PRESENT,
            profile_id_hash: surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_PROFILE_ID_HASH,
            main_particle_count:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_MAIN_PARTICLE_COUNT,
            tracer_state_capacity:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_TRACER_STATE_CAPACITY,
            tracer_draw_count:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_TRACER_DRAW_COUNT,
            retention_indicator_draw_count:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_RETENTION_INDICATOR_DRAW_COUNT,
            default_draw_count:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_DEFAULT_DRAW_COUNT,
            alias_policy_present:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_ALIAS_POLICY_PRESENT,
            public_runtime_packet_field_count:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_PUBLIC_RUNTIME_PACKET_FIELD_COUNT,
            active_alias_count:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_ACTIVE_ALIAS_COUNT,
            activation_gated_alias_count:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_ACTIVATION_GATED_ALIAS_COUNT,
            future_rejection_marker_count:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_FUTURE_REJECTION_MARKER_COUNT,
            forbidden_alias_payload_count:
                surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_FORBIDDEN_ALIAS_PAYLOAD_COUNT,
        }
    }

    fn any_configured(self) -> bool {
        self.profile_configured
            || self.shader_configured
            || self.payload_dir_configured
            || self.marker_prefix_configured
    }

    fn executable_inputs_configured(self) -> bool {
        self.profile_configured
            && self.shader_configured
            && self.payload_dir_configured
            && self.marker_prefix_configured
    }

    fn particle_count(self) -> u64 {
        let payload_count = (self.positions_byte_count / mem::size_of::<[f32; 4]>()).max(1);
        if self.profile_counts_present && self.main_particle_count > 0 {
            payload_count.min(self.main_particle_count) as u64
        } else {
            payload_count as u64
        }
    }

    fn tracer_state_capacity(self) -> u64 {
        if self.profile_counts_present {
            self.tracer_state_capacity as u64
        } else {
            0
        }
    }

    fn tracer_draw_count(self) -> u64 {
        if self.profile_counts_present {
            self.tracer_draw_count
                .min(self.tracer_state_capacity)
                .min(u32::MAX as usize) as u64
        } else {
            0
        }
    }

    fn retention_indicator_draw_count(self) -> u64 {
        0
    }

    fn draw_count(self) -> u64 {
        self.particle_count()
            .saturating_add(self.tracer_draw_count())
            .saturating_add(self.retention_indicator_draw_count())
            .min(u32::MAX as u64)
    }

    fn marker_fields(self) -> String {
        format!(
            "privateSurfaceParticleProfileConfigured={} privateSurfaceParticleShaderConfigured={} privateSurfaceParticlePayloadDirConfigured={} privateSurfaceParticleMarkerPrefixConfigured={} privateSurfaceParticleExecutableInputsConfigured={} privateSurfaceParticleShaderCompiled={} privateSurfaceParticleShaderBytes={} privateSurfaceParticlePayloadStageMode=build-time-copy-include-bytes privateSurfaceParticlePayloadFilesPresent={} privateSurfaceParticlePositionsBytes={} privateSurfaceParticleNormalsBytes={} privateSurfaceParticleAux0Bytes={} privateSurfaceParticleMaskTextureBytes={} privateSurfaceParticleStagedPayloadReady={} privateSurfaceParticleProfileCountsPresent={} privateSurfaceParticleProfileIdHash={:016x} privateSurfaceParticleProfileMainParticleCount={} privateSurfaceParticleProfileTracerStateCapacity={} privateSurfaceParticleProfileTracerDrawCount={} privateSurfaceParticleProfileRetentionIndicatorDrawCount={} privateSurfaceParticleProfileDefaultDrawCount={} privateSurfaceParticleUiParameterAliasPolicyPresent={} privateSurfaceParticleUiParameterPublicRuntimePacketFieldCount={} privateSurfaceParticleUiParameterActiveAliasCount={} privateSurfaceParticleUiParameterActivationGatedAliasCount={} privateSurfaceParticleUiParameterFutureRejectionMarkerCount={} privateSurfaceParticleUiParameterForbiddenAliasPayloadCount={} privateSurfaceParticleBuildMetadataReady=true",
            bool_token(self.profile_configured),
            bool_token(self.shader_configured),
            bool_token(self.payload_dir_configured),
            bool_token(self.marker_prefix_configured),
            bool_token(self.executable_inputs_configured()),
            bool_token(self.shader_compiled),
            self.shader_byte_count,
            bool_token(self.payload_files_present),
            self.positions_byte_count,
            self.normals_byte_count,
            self.aux0_byte_count,
            self.mask_texture_byte_count,
            bool_token(self.staged_payload_ready),
            bool_token(self.profile_counts_present),
            self.profile_id_hash,
            self.main_particle_count,
            self.tracer_state_capacity,
            self.tracer_draw_count,
            self.retention_indicator_draw_count,
            self.default_draw_count,
            bool_token(self.alias_policy_present),
            self.public_runtime_packet_field_count,
            self.active_alias_count,
            self.activation_gated_alias_count,
            self.future_rejection_marker_count,
            self.forbidden_alias_payload_count,
        )
    }

    fn visual_compact_marker_fields(self) -> String {
        format!(
            "privateSurfaceParticleProfileConfigured={} privateSurfaceParticlePayloadFilesPresent={} privateSurfaceParticleStagedPayloadReady={} privateSurfaceParticleProfileCountsPresent={} privateSurfaceParticleProfileIdHash={:016x} privateSurfaceParticleProfileMainParticleCount={} privateSurfaceParticleProfileTracerStateCapacity={} privateSurfaceParticleProfileTracerDrawCount={} privateSurfaceParticleProfileRetentionIndicatorDrawCount={} privateSurfaceParticleProfileDefaultDrawCount={} privateSurfaceParticleBuildMetadataReady=true",
            bool_token(self.profile_configured),
            bool_token(self.payload_files_present),
            bool_token(self.staged_payload_ready),
            bool_token(self.profile_counts_present),
            self.profile_id_hash,
            self.main_particle_count,
            self.tracer_state_capacity,
            self.tracer_draw_count,
            self.retention_indicator_draw_count,
            self.default_draw_count,
        )
    }
}

fn surface_particle_private_build_marker_fields() -> String {
    SurfaceParticlePrivateBuildConfig::current().marker_fields()
}

fn surface_particle_private_alias_compact_marker_fields() -> String {
    let build_config = SurfaceParticlePrivateBuildConfig::current();
    format!(
        "privateSurfaceParticleProfileConfigured={} privateSurfaceParticleStagedPayloadReady={} privateSurfaceParticleProfileIdHash={:016x} privateSurfaceParticleUiParameterAliasPolicyPresent={} privateSurfaceParticleUiParameterActiveAliasCount={} privateSurfaceParticleUiParameterActivationGatedAliasCount={} privateSurfaceParticleUiParameterForbiddenAliasPayloadCount={}",
        bool_token(build_config.profile_configured),
        bool_token(build_config.staged_payload_ready),
        build_config.profile_id_hash,
        bool_token(build_config.alias_policy_present),
        build_config.active_alias_count,
        build_config.activation_gated_alias_count,
        build_config.forbidden_alias_payload_count,
    )
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct SurfaceParticlePrivateRendererMetadata {
    build_config: SurfaceParticlePrivateBuildConfig,
}

impl SurfaceParticlePrivateRendererMetadata {
    fn from_build_config(build_config: SurfaceParticlePrivateBuildConfig) -> Self {
        Self { build_config }
    }

    fn marker_fields(self) -> String {
        format!(
            "privateSurfaceParticleMetadataMode=build-inputs-only privateSurfaceParticleMetadataValidationScope=public-build-hook privateSurfaceParticleMetadataComplete={} {}",
            bool_token(self.build_config.executable_inputs_configured()),
            self.build_config.marker_fields(),
        )
    }
}

enum SurfaceParticleRenderer {
    PublicHandAnchorProof(ReplayHandsRenderer),
    PrivateMetadataOnly(SurfaceParticlePrivateFallbackRenderer),
    PrivateMainDrawOnly(SurfaceParticlePrivateFallbackRenderer),
}

struct SurfaceParticlePrivateFallbackRenderer {
    metadata: SurfaceParticlePrivateRendererMetadata,
    public_fallback: Option<ReplayHandsRenderer>,
    mode: SurfaceParticleRendererMode,
}

impl SurfaceParticlePrivateFallbackRenderer {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        openxr_handles: LiveHandOpenXrHandles,
        metadata: SurfaceParticlePrivateRendererMetadata,
        mode: SurfaceParticleRendererMode,
    ) -> Result<Self, String> {
        let public_fallback = if mode == SurfaceParticleRendererMode::PrivateMainDrawOnly {
            None
        } else {
            Some(ReplayHandsRenderer::new(
                device,
                memory_properties,
                render_pass,
                openxr_handles,
            )?)
        };
        Ok(Self {
            metadata,
            public_fallback,
            mode,
        })
    }

    fn marker_fields(&self) -> String {
        let fallback_fields = match (&self.public_fallback, self.mode) {
            (None, SurfaceParticleRendererMode::PrivateMainDrawOnly) => {
                "surfaceParticleRendererMode=private-surface-particle-main-draw privateSurfaceParticleRendererMode=main-draw-only privateSurfaceParticleRendererSelection=private-main-draw-only-no-public-fallback privateSurfaceParticleMetadataActive=true privateSurfaceParticlePayloadActive=true privateSurfaceParticleMainComputeOnly=false privateSurfaceParticleMainDrawOnly=true privateSurfaceParticleVisible=true privateSurfaceParticleTracersActive=true privateSurfaceParticleExecutionReady=true privateSurfaceParticlePublicFallbackActive=false privatePayloadActive=true privatePayloadVisibility=private-main-draw-only privatePayloadDrawVisible=true"
                    .to_string()
            }
            (Some(renderer), _) => renderer.marker_fields(),
            (None, _) => {
                "surfaceParticleRendererMode=public-hand-anchor-proof privateSurfaceParticleRendererMode=metadata-only privateSurfaceParticleRendererSelection=private-metadata-only-no-public-fallback privateSurfaceParticleMetadataActive=true privateSurfaceParticlePayloadActive=false privateSurfaceParticleExecutionReady=false privateSurfaceParticlePublicFallbackActive=false"
                    .to_string()
            }
        };
        format!("{} {}", fallback_fields, self.metadata.marker_fields(),)
    }

    unsafe fn update_frame_resources(&mut self, device: &ash::Device) -> Result<(), String> {
        if let Some(public_fallback) = self.public_fallback.as_mut() {
            public_fallback.update_live_joints_buffer(device)
        } else {
            Ok(())
        }
    }

    #[allow(clippy::too_many_arguments)]
    unsafe fn record_eye(
        &self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        frame_counter: u32,
        eye_index: u32,
        time_seconds: f32,
        panel_projection: ReplayHandPanelProjection,
        driver0_value01: f32,
        driver1_value01: f32,
        point_scale: f32,
        live_hand_depth_offset_meters: f32,
        diagnostic_mode: u32,
    ) {
        if let Some(public_fallback) = self.public_fallback.as_ref() {
            public_fallback.record_eye(
                device,
                command_buffer,
                frame_counter,
                eye_index,
                time_seconds,
                panel_projection,
                driver0_value01,
                driver1_value01,
                point_scale,
                live_hand_depth_offset_meters,
                diagnostic_mode,
            );
        }
    }

    unsafe fn destroy(&mut self, device: &ash::Device) {
        if let Some(public_fallback) = self.public_fallback.as_mut() {
            public_fallback.destroy(device);
        }
    }
}

impl SurfaceParticleRenderer {
    unsafe fn new_from_build_metadata(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        openxr_handles: LiveHandOpenXrHandles,
    ) -> Result<Self, String> {
        let private_config = SurfaceParticlePrivateBuildConfig::current();
        let mode = selected_surface_particle_renderer_mode(private_config);
        if mode != SurfaceParticleRendererMode::PublicHandAnchorProof {
            let metadata =
                SurfaceParticlePrivateRendererMetadata::from_build_config(private_config);
            let (status, placeholder_status, selection) = match mode {
                SurfaceParticleRendererMode::PrivateMainDrawOnly => (
                    "private-surface-particle-main-draw-only",
                    "private-surface-particle-main-draw-no-public-fallback",
                    SurfaceParticleRendererMode::PrivateMainDrawOnly.marker_fields(),
                ),
                _ => (
                    "private-surface-particle-metadata-only-placeholder",
                    "private-surface-particle-placeholder-unavailable",
                    SurfaceParticleRendererMode::PrivateMetadataOnly.marker_fields(),
                ),
            };
            android_log_info(
                "RQSpatialCameraPanelNative",
                &format!(
                    "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status={} renderPolicy=native-vulkan-wsi-surface-panel privateSurfaceParticlePlaceholderStatus={} {} {}",
                    status,
                    placeholder_status,
                    selection,
                    metadata.marker_fields(),
                ),
            );
            let renderer = SurfaceParticlePrivateFallbackRenderer::new(
                device,
                memory_properties,
                render_pass,
                openxr_handles,
                metadata,
                mode,
            )?;
            return Ok(match mode {
                SurfaceParticleRendererMode::PrivateMainDrawOnly => {
                    Self::PrivateMainDrawOnly(renderer)
                }
                _ => Self::PrivateMetadataOnly(renderer),
            });
        }
        android_log_info(
            "RQSpatialCameraPanelNative",
            &format!(
                "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=public-hand-anchor-renderer-selected renderPolicy=native-vulkan-wsi-surface-panel particleRendererModeProperty={} particleRendererModeSource=runtime-or-build-default {} {}",
                SURFACE_PARTICLE_RENDERER_MODE_PROPERTY,
                SurfaceParticleRendererMode::PublicHandAnchorProof.marker_fields(),
                private_config.marker_fields(),
            ),
        );
        Self::new_public_default(device, memory_properties, render_pass, openxr_handles)
    }

    unsafe fn new_public_default(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        openxr_handles: LiveHandOpenXrHandles,
    ) -> Result<Self, String> {
        Ok(Self::PublicHandAnchorProof(ReplayHandsRenderer::new(
            device,
            memory_properties,
            render_pass,
            openxr_handles,
        )?))
    }

    fn mode(&self) -> SurfaceParticleRendererMode {
        match self {
            Self::PublicHandAnchorProof(_) => SurfaceParticleRendererMode::PublicHandAnchorProof,
            Self::PrivateMetadataOnly(_) => SurfaceParticleRendererMode::PrivateMetadataOnly,
            Self::PrivateMainDrawOnly(_) => SurfaceParticleRendererMode::PrivateMainDrawOnly,
        }
    }

    fn marker_fields(&self) -> String {
        match self {
            Self::PublicHandAnchorProof(renderer) => renderer.marker_fields(),
            Self::PrivateMetadataOnly(renderer) | Self::PrivateMainDrawOnly(renderer) => {
                renderer.marker_fields()
            }
        }
    }

    unsafe fn update_frame_resources(&mut self, device: &ash::Device) -> Result<(), String> {
        match self {
            Self::PublicHandAnchorProof(renderer) => renderer.update_live_joints_buffer(device),
            Self::PrivateMetadataOnly(renderer) | Self::PrivateMainDrawOnly(renderer) => {
                renderer.update_frame_resources(device)
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    unsafe fn record_eye(
        &self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        frame_counter: u32,
        eye_index: u32,
        time_seconds: f32,
        panel_projection: ReplayHandPanelProjection,
        driver0_value01: f32,
        driver1_value01: f32,
        point_scale: f32,
        live_hand_depth_offset_meters: f32,
        diagnostic_mode: u32,
    ) {
        match self {
            Self::PublicHandAnchorProof(renderer) => renderer.record_eye(
                device,
                command_buffer,
                frame_counter,
                eye_index,
                time_seconds,
                panel_projection,
                driver0_value01,
                driver1_value01,
                point_scale,
                live_hand_depth_offset_meters,
                diagnostic_mode,
            ),
            Self::PrivateMetadataOnly(renderer) | Self::PrivateMainDrawOnly(renderer) => renderer
                .record_eye(
                    device,
                    command_buffer,
                    frame_counter,
                    eye_index,
                    time_seconds,
                    panel_projection,
                    driver0_value01,
                    driver1_value01,
                    point_scale,
                    live_hand_depth_offset_meters,
                    diagnostic_mode,
                ),
        }
    }

    unsafe fn destroy(&mut self, device: &ash::Device) {
        match self {
            Self::PublicHandAnchorProof(renderer) => renderer.destroy(device),
            Self::PrivateMetadataOnly(renderer) | Self::PrivateMainDrawOnly(renderer) => {
                renderer.destroy(device)
            }
        }
    }
}

#[link(name = "android")]
extern "C" {
    fn ANativeWindow_fromSurface(env: *mut c_void, surface: *mut c_void) -> *mut vk::ANativeWindow;
}

#[cfg(not(target_os = "android"))]
#[link(name = "android")]
extern "C" {
    fn ANativeWindow_release(window: *mut vk::ANativeWindow);
}

#[cfg(target_os = "android")]
extern "C" {
    fn __system_property_get(name: *const c_char, value: *mut c_char) -> c_int;
}

unsafe fn release_anative_window(window: *mut vk::ANativeWindow) {
    #[cfg(target_os = "android")]
    {
        release_camera_native_window(window.cast::<CameraNativeWindow>());
    }
    #[cfg(not(target_os = "android"))]
    {
        ANativeWindow_release(window);
    }
}

struct ExternalSwapchainOpenXrFunction {
    resolved: bool,
    result: String,
    function: Option<openxr_sys::pfn::VoidFunction>,
}

struct SurfaceParticleOpenXrViewMapper {
    instance: openxr_sys::Instance,
    session: openxr_sys::Session,
    local_space: openxr_sys::Space,
    locate_views: Option<openxr_sys::pfn::LocateViews>,
    destroy_space: Option<openxr_sys::pfn::DestroySpace>,
    convert_timespec_time_to_time: Option<openxr_sys::pfn::ConvertTimespecTimeToTimeKHR>,
    ready: bool,
    reference_space_type: &'static str,
    status: String,
    time_source: &'static str,
    unavailable_logged: bool,
}

impl SurfaceParticleOpenXrViewMapper {
    unsafe fn new(handles: LiveHandOpenXrHandles) -> Self {
        if handles.instance_handle == 0
            || handles.session_handle == 0
            || handles.get_instance_proc_addr_handle == 0
        {
            return Self::unavailable("missing-openxr-handles");
        }
        let instance = openxr_sys::Instance::from_raw(handles.instance_handle as u64);
        let session = openxr_sys::Session::from_raw(handles.session_handle as u64);
        let get_instance_proc_addr: openxr_sys::pfn::GetInstanceProcAddr =
            mem::transmute(handles.get_instance_proc_addr_handle as usize);
        let locate_views_resolution = resolve_external_swapchain_openxr_function(
            instance,
            get_instance_proc_addr,
            "xrLocateViews",
        );
        let create_reference_space_resolution = resolve_external_swapchain_openxr_function(
            instance,
            get_instance_proc_addr,
            "xrCreateReferenceSpace",
        );
        let destroy_space_resolution = resolve_external_swapchain_openxr_function(
            instance,
            get_instance_proc_addr,
            "xrDestroySpace",
        );
        let convert_timespec_resolution = resolve_external_swapchain_openxr_function(
            instance,
            get_instance_proc_addr,
            "xrConvertTimespecTimeToTimeKHR",
        );
        if !locate_views_resolution.resolved {
            return Self::unavailable(&format!("xrLocateViews-{}", locate_views_resolution.result));
        }
        if !create_reference_space_resolution.resolved || !destroy_space_resolution.resolved {
            return Self::unavailable(&format!(
                "reference-space-functions-create_{}_destroy_{}",
                create_reference_space_resolution.result, destroy_space_resolution.result
            ));
        }
        let create_reference_space: openxr_sys::pfn::CreateReferenceSpace =
            external_swapchain_typed_function(create_reference_space_resolution.function)
                .expect("resolved xrCreateReferenceSpace");
        let (local_space, reference_space_type) =
            match surface_particle_create_reference_space(session, create_reference_space) {
                Ok(value) => value,
                Err(error) => return Self::unavailable(&error),
            };
        let locate_views = external_swapchain_typed_function(locate_views_resolution.function);
        let destroy_space = external_swapchain_typed_function(destroy_space_resolution.function);
        let convert_timespec_time_to_time =
            external_swapchain_typed_function(convert_timespec_resolution.function);
        let mut mapper = Self {
            instance,
            session,
            local_space,
            locate_views,
            destroy_space,
            convert_timespec_time_to_time,
            ready: true,
            reference_space_type,
            status: "ready".to_string(),
            time_source: "pending",
            unavailable_logged: false,
        };
        mapper.log_status("private-openxr-world-anchor-mapper-ready");
        mapper
    }

    fn unavailable(reason: &str) -> Self {
        Self {
            instance: openxr_sys::Instance::NULL,
            session: openxr_sys::Session::NULL,
            local_space: openxr_sys::Space::NULL,
            locate_views: None,
            destroy_space: None,
            convert_timespec_time_to_time: None,
            ready: false,
            reference_space_type: "unavailable",
            status: reason.to_string(),
            time_source: "unavailable",
            unavailable_logged: false,
        }
    }

    fn current_mapping(
        &mut self,
        panel_projection: ReplayHandPanelProjection,
    ) -> Option<SurfaceParticleOpenXrPanelMapping> {
        if !self.ready || self.local_space == openxr_sys::Space::NULL {
            self.log_unavailable_once();
            return None;
        }
        let Some(time) = self.current_xr_time() else {
            return None;
        };
        let Some(locate_views) = self.locate_views else {
            self.status = "locate-views-function-missing".to_string();
            return None;
        };
        let locate_info = openxr_sys::ViewLocateInfo {
            ty: openxr_sys::ViewLocateInfo::TYPE,
            next: ptr::null(),
            view_configuration_type: openxr_sys::ViewConfigurationType::PRIMARY_STEREO,
            display_time: time,
            space: self.local_space,
        };
        let mut view_state = openxr_sys::ViewState {
            ty: openxr_sys::ViewState::TYPE,
            next: ptr::null_mut(),
            view_state_flags: openxr_sys::ViewStateFlags::from_raw(0),
        };
        let mut views = [
            surface_particle_default_openxr_view(),
            surface_particle_default_openxr_view(),
        ];
        let mut view_count = 0_u32;
        let result = unsafe {
            locate_views(
                self.session,
                &locate_info,
                &mut view_state,
                views.len() as u32,
                &mut view_count,
                views.as_mut_ptr(),
            )
        };
        if result != openxr_sys::Result::SUCCESS {
            self.status = format!(
                "locate-views-{}",
                external_swapchain_xr_result_token(result)
            );
            return None;
        }
        if view_count < 1 {
            self.status = "locate-views-empty".to_string();
            return None;
        }
        if !view_state
            .view_state_flags
            .contains(openxr_sys::ViewStateFlags::POSITION_VALID)
            || !view_state
                .view_state_flags
                .contains(openxr_sys::ViewStateFlags::ORIENTATION_VALID)
        {
            self.status = format!(
                "locate-views-invalid-flags-{:?}",
                view_state.view_state_flags
            );
            return None;
        }
        let view_count_usize = (view_count as usize).min(views.len());
        let mut view_position = [0.0_f32; 3];
        for view in views.iter().take(view_count_usize) {
            view_position[0] += view.pose.position.x;
            view_position[1] += view.pose.position.y;
            view_position[2] += view.pose.position.z;
        }
        let inv_view_count = 1.0 / view_count_usize.max(1) as f32;
        view_position[0] *= inv_view_count;
        view_position[1] *= inv_view_count;
        view_position[2] *= inv_view_count;
        let view_orientation = [
            views[0].pose.orientation.x,
            views[0].pose.orientation.y,
            views[0].pose.orientation.z,
            views[0].pose.orientation.w,
        ];
        let Some(view_orientation) = normalize_quat_or_none(view_orientation) else {
            self.status = "locate-views-orientation-invalid".to_string();
            return None;
        };
        let panel_forward = surface_particle_panel_forward(panel_projection);
        self.status = format!("ready-view-count-{}", view_count_usize);
        Some(SurfaceParticleOpenXrPanelMapping {
            view_position,
            raw_right: rotate_vec3_by_quat(view_orientation, [1.0, 0.0, 0.0]),
            raw_up: rotate_vec3_by_quat(view_orientation, [0.0, 1.0, 0.0]),
            raw_forward: rotate_vec3_by_quat(view_orientation, [0.0, 0.0, -1.0]),
            scene_eye_position: add3(
                panel_projection.center,
                scale3(panel_forward, -panel_projection.target_distance_meters),
            ),
            panel_right: panel_projection.right,
            panel_up: panel_projection.up,
            panel_forward,
        })
    }

    fn current_xr_time(&mut self) -> Option<openxr_sys::Time> {
        let mut timespec = libc::timespec {
            tv_sec: 0,
            tv_nsec: 0,
        };
        let clock_result = unsafe { clock_gettime(CLOCK_MONOTONIC, &mut timespec) };
        if clock_result != 0 {
            self.status = "clock_gettime-failed".to_string();
            self.time_source = "clock_gettime-failed";
            return None;
        }
        if let Some(convert) = self.convert_timespec_time_to_time {
            let mut time = openxr_sys::Time::from_nanos(0);
            let result = unsafe { convert(self.instance, &timespec, &mut time) };
            if result == openxr_sys::Result::SUCCESS {
                self.time_source = "XR_KHR_convert_timespec_time_CLOCK_MONOTONIC";
                return Some(time);
            }
            self.status = format!(
                "xrConvertTimespecTimeToTimeKHR-{}",
                external_swapchain_xr_result_token(result)
            );
        }
        let nanos = timespec
            .tv_sec
            .saturating_mul(1_000_000_000)
            .saturating_add(timespec.tv_nsec as i64);
        self.time_source = "CLOCK_MONOTONIC_direct_XrTime_fallback";
        Some(openxr_sys::Time::from_nanos(nanos))
    }

    fn marker_fields(&self) -> String {
        format!(
            "privateSurfaceParticleWorldAnchorMapper=openxr-local-floor-view-panel-mapping privateSurfaceParticleOpenXrViewMapperReady={} privateSurfaceParticleOpenXrReferenceSpaceType={} privateSurfaceParticleOpenXrViewLocateStatus={} privateSurfaceParticleOpenXrTimeSource={} privateSurfaceParticlePublicFallbackActive=false",
            bool_token(self.ready),
            marker_token(self.reference_space_type),
            marker_token(&self.status),
            marker_token(self.time_source),
        )
    }

    fn log_status(&mut self, status: &'static str) {
        android_log_info(
            "RQSpatialCameraPanelNative",
            &format!(
                "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status={} renderPolicy=native-vulkan-wsi-surface-panel {}",
                status,
                self.marker_fields(),
            ),
        );
    }

    fn log_unavailable_once(&mut self) {
        if self.unavailable_logged {
            return;
        }
        self.unavailable_logged = true;
        self.log_status("private-openxr-world-anchor-mapper-unavailable");
    }

    unsafe fn destroy(&mut self) {
        if self.local_space != openxr_sys::Space::NULL {
            if let Some(destroy_space) = self.destroy_space {
                let _ = destroy_space(self.local_space);
            }
            self.local_space = openxr_sys::Space::NULL;
        }
        self.ready = false;
    }
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeCreateExternalOpenXrSwapchain(
    _env: *mut c_void,
    _thiz: *mut c_void,
    openxr_instance_handle: i64,
    openxr_session_handle: i64,
    openxr_get_instance_proc_addr_handle: i64,
    width: c_int,
    height: c_int,
) -> i64 {
    let result = unsafe {
        create_external_openxr_swapchain(
            openxr_instance_handle,
            openxr_session_handle,
            openxr_get_instance_proc_addr_handle,
            width.max(1) as u32,
            height.max(1) as u32,
        )
    };
    match result {
        Ok(swapchain_handle) => swapchain_handle as i64,
        Err(error) => {
            android_log_info(
                "RQSpatialCameraPanelNative",
                &format!(
                    "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=external-xr-swapchain-wrap-probe status=native-create-failed externalSwapchainProbe=true xrCreateSwapchainResult={} wrappedExternalSwapchain=false sceneQuadLayerCreated=false swapchainImagesEnumerated=0 nativeCanRenderIntoImages=false visiblePatternConfirmed=false deviceLost=false runtimeCrash=false error={}",
                    marker_token(&error),
                    marker_token(&error),
                ),
            );
            0
        }
    }
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeDestroyExternalOpenXrSwapchain(
    _env: *mut c_void,
    _thiz: *mut c_void,
    openxr_instance_handle: i64,
    openxr_get_instance_proc_addr_handle: i64,
    swapchain_handle: i64,
) -> c_int {
    let result = unsafe {
        destroy_external_openxr_swapchain(
            openxr_instance_handle,
            openxr_get_instance_proc_addr_handle,
            swapchain_handle,
        )
    };
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=external-xr-swapchain-wrap-probe status=native-destroy-result externalSwapchainProbe=true swapchainHandle={} xrDestroySwapchainResult={} xrDestroySwapchainCode={} destroyOwnershipHint={} deviceLost=false runtimeCrash=false",
            swapchain_handle,
            external_swapchain_xr_result_token(openxr_sys::Result::from_raw(result)),
            result,
            if result == openxr_sys::Result::SUCCESS.into_raw() {
                "native"
            } else if result == openxr_sys::Result::ERROR_HANDLE_INVALID.into_raw() {
                "sdk"
            } else {
                "unknown"
            },
        ),
    );
    result
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStartSdkQuadVulkanProbe(
    env: *mut c_void,
    _thiz: *mut c_void,
    surface: *mut c_void,
    width: c_int,
    height: c_int,
    frame_count: c_int,
) -> i64 {
    let mut mask = START_RECEIVED;
    if !surface.is_null() {
        mask |= START_SURFACE_NON_NULL;
    }
    if surface.is_null() || env.is_null() {
        android_log_info(
            "RQSpatialCameraPanelNative",
            &format!(
                "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=sdk-owned-quad-vulkan-probe status=start-receipt nativeVulkanProducer=true startMask={} startStatus=missing-env-or-surface surfaceNonNull={} nativeWindowObtained=false renderThreadSpawned=false runtimeCrash=false",
                mask,
                bool_token(!surface.is_null()),
            ),
        );
        return mask;
    }

    let window = unsafe { ANativeWindow_fromSurface(env, surface) };
    if window.is_null() {
        android_log_info(
            "RQSpatialCameraPanelNative",
            &format!(
                "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=sdk-owned-quad-vulkan-probe status=start-receipt nativeVulkanProducer=true startMask={} startStatus=native-window-null surfaceNonNull=true nativeWindowObtained=false renderThreadSpawned=false runtimeCrash=false",
                mask,
            ),
        );
        return mask;
    }
    mask |= START_NATIVE_WINDOW_OBTAINED;
    STOP_SDK_QUAD_VULKAN_PROBE.store(false, Ordering::Relaxed);

    let window_addr = window as usize;
    let width = width.max(64) as u32;
    let height = height.max(64) as u32;
    let max_frames = frame_count.clamp(1, 1800) as u32;
    let spawn_result = thread::Builder::new()
        .name("spatial-camera-panel-quad-vulkan-probe".to_string())
        .spawn(move || {
            let window = window_addr as *mut vk::ANativeWindow;
            let started = Instant::now();
            let render_result = std::panic::catch_unwind(|| unsafe {
                render_sdk_quad_vulkan_probe(window, width, height, max_frames)
            });
            let result = match render_result {
                Ok(result) => result,
                Err(_) => Err("panic".to_string()),
            };
            unsafe {
                release_anative_window(window);
            }
            match result {
                Ok(stats) => {
                    android_log_info(
                        "RQSpatialCameraPanelNative",
                        &format!(
                            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=sdk-owned-quad-vulkan-probe status=render-complete nativeVulkanProducer=true renderPolicy=sdk-owned-scenequadlayer-android-surface-wsi framesPresented={} requestedFrames={} extent={}x{} swapchainImages={} presentResult=success recreateDestroyResult=destroyed elapsedMs={} runtimeCrash=false",
                            stats.frames_presented,
                            max_frames,
                            stats.extent.width,
                            stats.extent.height,
                            stats.swapchain_image_count,
                            started.elapsed().as_millis(),
                        ),
                    );
                }
                Err(error) => {
                    android_log_info(
                        "RQSpatialCameraPanelNative",
                        &format!(
                            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=sdk-owned-quad-vulkan-probe status=render-failed nativeVulkanProducer=true renderPolicy=sdk-owned-scenequadlayer-android-surface-wsi error={} runtimeCrash=false",
                            marker_token(&error),
                        ),
                    );
                }
            }
        });

    match spawn_result {
        Ok(_) => {
            mask |= START_RENDER_THREAD_SPAWNED;
            android_log_info(
                "RQSpatialCameraPanelNative",
                &format!(
                    "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=sdk-owned-quad-vulkan-probe status=start-receipt nativeVulkanProducer=true startMask={} startStatus=started surfaceNonNull=true nativeWindowObtained=true renderThreadSpawned=true requestedWidthPx={} requestedHeightPx={} requestedFrames={} runtimeCrash=false",
                    mask,
                    width,
                    height,
                    max_frames,
                ),
            );
        }
        Err(error) => {
            unsafe {
                release_anative_window(window);
            }
            android_log_info(
                "RQSpatialCameraPanelNative",
                &format!(
                    "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=sdk-owned-quad-vulkan-probe status=start-receipt nativeVulkanProducer=true startMask={} startStatus=thread-spawn-{} surfaceNonNull=true nativeWindowObtained=true renderThreadSpawned=false runtimeCrash=false",
                    mask,
                    error.kind(),
                ),
            );
        }
    }
    mask
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStopSdkQuadVulkanProbe(
    _env: *mut c_void,
    _thiz: *mut c_void,
) {
    STOP_SDK_QUAD_VULKAN_PROBE.store(true, Ordering::Relaxed);
    android_log_info(
        "RQSpatialCameraPanelNative",
        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=sdk-owned-quad-vulkan-probe status=stop-requested nativeVulkanProducer=true renderPolicy=sdk-owned-scenequadlayer-android-surface-wsi runtimeCrash=false",
    );
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStartSurfaceParticleLayer(
    env: *mut c_void,
    _thiz: *mut c_void,
    surface: *mut c_void,
    width: c_int,
    height: c_int,
    particle_count: c_int,
    frame_count: c_int,
    openxr_instance_handle: i64,
    openxr_session_handle: i64,
    openxr_get_instance_proc_addr_handle: i64,
) -> i64 {
    let mut mask = START_RECEIVED;
    if !surface.is_null() {
        mask |= START_SURFACE_NON_NULL;
    }
    if surface.is_null() || env.is_null() {
        log_start_receipt(mask, "missing-env-or-surface");
        return mask;
    }

    let window = unsafe { ANativeWindow_fromSurface(env, surface) };
    if window.is_null() {
        log_start_receipt(mask, "native-window-null");
        return mask;
    }
    mask |= START_NATIVE_WINDOW_OBTAINED;
    STOP_SURFACE_PARTICLES.store(false, Ordering::Relaxed);
    reset_surface_particle_world_anchor();

    let window_addr = window as usize;
    let width = width.max(256) as u32;
    let height = height.max(256) as u32;
    let particle_count = particle_count.clamp(128, 16_384) as u32;
    let max_frames = frame_count.max(0) as u32;
    let openxr_handles = LiveHandOpenXrHandles {
        instance_handle: openxr_instance_handle,
        session_handle: openxr_session_handle,
        get_instance_proc_addr_handle: openxr_get_instance_proc_addr_handle,
    };
    let spawn_result = thread::Builder::new()
        .name("spatial-camera-panel-surface-particles".to_string())
        .spawn(move || {
            let window = window_addr as *mut vk::ANativeWindow;
            let started = Instant::now();
            let render_result = std::panic::catch_unwind(|| unsafe {
                render_surface_particles(
                    window,
                    width,
                    height,
                    particle_count,
                    max_frames,
                    openxr_handles,
                )
            });
            let result = match render_result {
                Ok(result) => result,
                Err(_) => Err("panic".to_string()),
            };
            unsafe {
                release_anative_window(window);
            }
            match result {
                Ok(stats) => {
                    let stereo_layout = surface_particle_stereo_layout(stats.extent);
                    android_log_info(
                        "RQSpatialCameraPanelNative",
                        &format!(
                            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=render-complete renderPolicy=native-vulkan-wsi-surface-panel framesPresented={} particleCount={} extent={}x{} stereoMode={} perEyeExtent={}x{} packedExtent={}x{} {} {} {} elapsedMs={}",
                            stats.frames_presented,
                            stats.particle_count,
                            stats.extent.width,
                            stats.extent.height,
                            stereo_layout.stereo_mode,
                            stereo_layout.per_eye_extent.width,
                            stereo_layout.per_eye_extent.height,
                            stereo_layout.packed_extent.width,
                            stereo_layout.packed_extent.height,
                            SURFACE_PARTICLE_PROJECTION_MARKERS,
                            stats.renderer_mode.marker_fields(),
                            surface_particle_private_build_marker_fields(),
                            started.elapsed().as_millis(),
                        ),
                    );
                }
                Err(error) => {
                    android_log_info(
                        "RQSpatialCameraPanelNative",
                        &format!(
                            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=render-failed renderPolicy=native-vulkan-wsi-surface-panel {} {} error={}",
                            current_surface_particle_renderer_mode().marker_fields(),
                            surface_particle_private_build_marker_fields(),
                            marker_token(&error),
                        ),
                    );
                }
            }
        });

    match spawn_result {
        Ok(_) => {
            mask |= START_RENDER_THREAD_SPAWNED;
            log_start_receipt(mask, "started");
        }
        Err(error) => {
            unsafe {
                release_anative_window(window);
            }
            log_start_receipt(mask, &format!("thread-spawn-{}", error.kind()));
        }
    }
    mask
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStopSurfaceParticleLayer(
    _env: *mut c_void,
    _thiz: *mut c_void,
) {
    STOP_SURFACE_PARTICLES.store(true, Ordering::Relaxed);
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=stop-requested renderPolicy=native-vulkan-wsi-surface-panel {} {}",
            current_surface_particle_renderer_mode().marker_fields(),
            surface_particle_private_build_marker_fields(),
        ),
    );
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdateSurfaceParticleParameters(
    _env: *mut c_void,
    _thiz: *mut c_void,
    driver0_value01: c_float,
    driver1_value01: c_float,
    point_scale: c_float,
    driver2_value01: c_float,
    driver3_value01: c_float,
    driver4_value01: c_float,
    driver5_value01: c_float,
    driver6_value01: c_float,
    driver7_value01: c_float,
    tracer_draw_slots_per_oscillator: c_float,
    tracer_lifetime_seconds: c_float,
    tracer_copies_per_second: c_float,
    transparency_opacity: c_float,
    projection_world_scale: c_float,
) -> i64 {
    let revision = SURFACE_PARTICLE_PARAMETER_REVISION.fetch_add(1, Ordering::Relaxed) + 1;
    let params = SurfaceParticleParameters {
        revision,
        driver0_value01: clamp_f32(driver0_value01, 0.0, 1.0),
        driver1_value01: clamp_f32(driver1_value01, 0.0, 1.0),
        driver2_value01: clamp_f32(driver2_value01, 0.0, 1.0),
        driver3_value01: clamp_f32(driver3_value01, 0.0, 1.0),
        driver4_value01: clamp_f32(driver4_value01, 0.0, 1.0),
        driver5_value01: clamp_f32(driver5_value01, 0.0, 1.0),
        driver6_value01: clamp_f32(driver6_value01, 0.0, 1.0),
        driver7_value01: clamp_f32(driver7_value01, 0.0, 1.0),
        point_scale: clamp_f32(point_scale, 0.35, 2.25),
        tracer_draw_slots_per_oscillator: clamp_f32(tracer_draw_slots_per_oscillator, 0.0, 7.0),
        tracer_lifetime_seconds: clamp_f32(tracer_lifetime_seconds, 0.0, 0.5),
        tracer_copies_per_second: clamp_f32(tracer_copies_per_second, 0.0, 14.0),
        transparency_opacity: clamp_f32(transparency_opacity, 0.0, 1.0),
        projection_world_scale: clamp_f32(projection_world_scale, 0.5, 2.0),
        live_hand_depth_offset_meters: f32::from_bits(
            SURFACE_PARTICLE_LIVE_HAND_DEPTH_OFFSET_BITS.load(Ordering::Relaxed),
        ),
        diagnostic_mode: SURFACE_PARTICLE_DIAGNOSTIC_MODE.load(Ordering::Relaxed),
    };
    SURFACE_PARTICLE_DRIVER0_BITS.store(params.driver0_value01.to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_DRIVER1_BITS.store(params.driver1_value01.to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_DRIVER2_BITS.store(params.driver2_value01.to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_DRIVER3_BITS.store(params.driver3_value01.to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_DRIVER4_BITS.store(params.driver4_value01.to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_DRIVER5_BITS.store(params.driver5_value01.to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_DRIVER6_BITS.store(params.driver6_value01.to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_DRIVER7_BITS.store(params.driver7_value01.to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_POINT_SCALE_BITS.store(params.point_scale.to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_TRACER_DRAW_SLOTS_BITS.store(
        params.tracer_draw_slots_per_oscillator.to_bits(),
        Ordering::Relaxed,
    );
    SURFACE_PARTICLE_TRACER_LIFETIME_SECONDS_BITS
        .store(params.tracer_lifetime_seconds.to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_TRACER_COPIES_PER_SECOND_BITS
        .store(params.tracer_copies_per_second.to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_TRANSPARENCY_OPACITY_BITS
        .store(params.transparency_opacity.to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_PROJECTION_WORLD_SCALE_BITS
        .store(params.projection_world_scale.to_bits(), Ordering::Relaxed);
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=parameters-updated renderPolicy=native-vulkan-wsi-surface-panel transport=jni-live-queue computeParameterBridge=true privateSurfaceParticleUiParameterPacketReady=true privateSurfaceParticleUiParameterTransport=jni-live-queue privateSurfaceParticleUiParameterHighRatePayloadAllowed=false privateSurfaceParticleUiParameterRejected=false privateSurfaceParticleUiParameterRejectReason=none privateSurfaceParticleUiParameterRequestRevision={} privateSurfaceParticleUiParameterAcceptRevision={} {} {} liveHandDepthOffsetParameterSource=runtime-hotload-android-property liveHandDepthOffsetProperty={} particleDiagnosticModeProperty={} particleDiagnosticMode={} particleDiagnosticModeName={} driver0Value01={:.3} driver1Value01={:.3} driver2Value01={:.3} driver3Value01={:.3} driver4Value01={:.3} driver5Value01={:.3} driver6Value01={:.3} driver7Value01={:.3} pointScale={:.3} tracerDrawSlotsPerOscillator={:.3} tracerLifetimeSeconds={:.3} tracerCopiesPerSecond={:.3} transparencyOpacity={:.3} projectionWorldScale={:.3} liveHandDepthOffsetMeters={:.3}",
            params.revision,
            params.revision,
            current_surface_particle_renderer_mode().marker_fields(),
            surface_particle_private_build_marker_fields(),
            SURFACE_PARTICLE_LIVE_HAND_DEPTH_OFFSET_PROPERTY,
            SURFACE_PARTICLE_DIAGNOSTIC_MODE_PROPERTY,
            params.diagnostic_mode,
            surface_particle_diagnostic_mode_name(params.diagnostic_mode),
            params.driver0_value01,
            params.driver1_value01,
            params.driver2_value01,
            params.driver3_value01,
            params.driver4_value01,
            params.driver5_value01,
            params.driver6_value01,
            params.driver7_value01,
            params.point_scale,
            params.tracer_draw_slots_per_oscillator,
            params.tracer_lifetime_seconds,
            params.tracer_copies_per_second,
            params.transparency_opacity,
            params.projection_world_scale,
            params.live_hand_depth_offset_meters,
        ),
    );
    0x3fff
}

#[cfg(target_os = "android")]
#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeResolveSurfaceParticleAliasParameter(
    env: *mut jni::sys::JNIEnv,
    _thiz: jni::sys::jobject,
    parameter_id: jni::sys::jstring,
    value: jni::sys::jfloat,
    visual_driver_activation_profile: jni::sys::jstring,
) -> i64 {
    let parameter_id = jstring_to_string(env, parameter_id);
    let visual_driver_activation_profile = jstring_to_string(env, visual_driver_activation_profile);
    resolve_surface_particle_alias_parameter(
        &parameter_id,
        value,
        &visual_driver_activation_profile,
    )
}

fn resolve_surface_particle_alias_parameter(
    parameter_id: &str,
    value: f32,
    visual_driver_activation_profile: &str,
) -> i64 {
    let revision = SURFACE_PARTICLE_PARAMETER_REVISION.fetch_add(1, Ordering::Relaxed) + 1;
    let parameter_id = parameter_id.trim();
    let selected_profile = visual_driver_activation_profile.trim();
    let profile_json = surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_PROFILE_JSON;
    if profile_json.trim().is_empty() {
        log_surface_particle_alias_rejection(
            revision,
            parameter_id,
            selected_profile,
            value,
            "alias-policy-unavailable",
            "",
            0,
        );
        return ALIAS_REQUEST_RECEIVED | ALIAS_REQUEST_REJECTED;
    }
    let Ok(profile) = serde_json::from_str::<serde_json::Value>(profile_json) else {
        log_surface_particle_alias_rejection(
            revision,
            parameter_id,
            selected_profile,
            value,
            "alias-policy-json-invalid",
            "",
            ALIAS_POLICY_PRESENT,
        );
        return ALIAS_REQUEST_RECEIVED | ALIAS_POLICY_PRESENT | ALIAS_REQUEST_REJECTED;
    };
    let Some(alias_policy) = profile
        .get("runtime_parameter_alias_policy")
        .and_then(serde_json::Value::as_object)
    else {
        log_surface_particle_alias_rejection(
            revision,
            parameter_id,
            selected_profile,
            value,
            "alias-policy-missing",
            "",
            ALIAS_POLICY_PRESENT,
        );
        return ALIAS_REQUEST_RECEIVED | ALIAS_POLICY_PRESENT | ALIAS_REQUEST_REJECTED;
    };
    let mut mask = ALIAS_REQUEST_RECEIVED | ALIAS_POLICY_PRESENT;
    if json_string_array_contains(alias_policy.get("forbidden_alias_payloads"), parameter_id) {
        log_surface_particle_alias_rejection(
            revision,
            parameter_id,
            selected_profile,
            value,
            "high-rate-payload-forbidden",
            "",
            mask,
        );
        return mask | ALIAS_REQUEST_REJECTED;
    }
    if find_alias_by_parameter_id(alias_policy.get("rebuild_scoped_aliases"), parameter_id)
        .is_some()
    {
        log_surface_particle_alias_rejection(
            revision,
            parameter_id,
            selected_profile,
            value,
            "rebuild-scoped-choice-not-live-ui",
            "",
            mask,
        );
        return mask | ALIAS_REQUEST_REJECTED;
    }

    let active_alias = find_alias_by_parameter_id(alias_policy.get("active_aliases"), parameter_id)
        .map(|alias| (alias, "active-alias"));
    let derived_alias =
        find_alias_by_parameter_id(alias_policy.get("profile_derived_aliases"), parameter_id)
            .map(|alias| (alias, "profile-derived-alias-active"));
    let gated_alias =
        find_alias_by_parameter_id(alias_policy.get("activation_gated_aliases"), parameter_id);
    let (alias, alias_status) = if let Some(alias) = active_alias {
        alias
    } else if let Some(alias) = derived_alias {
        alias
    } else if let Some(alias) = gated_alias {
        if !json_string_array_contains(
            alias.get("active_when_visual_driver_activation_profiles"),
            selected_profile,
        ) {
            let required_activation_profile = json_string(alias.get("activation_requires"));
            log_surface_particle_alias_rejection(
                revision,
                parameter_id,
                selected_profile,
                value,
                "inactive-private-alias",
                &required_activation_profile,
                mask,
            );
            return mask | ALIAS_REQUEST_REJECTED;
        }
        (alias, "activation-gated-alias-active")
    } else {
        log_surface_particle_alias_rejection(
            revision,
            parameter_id,
            selected_profile,
            value,
            "unknown-parameter",
            "",
            mask,
        );
        return mask | ALIAS_REQUEST_REJECTED;
    };

    let public_field = json_string(alias.get("public_field"));
    if public_field.is_empty() {
        log_surface_particle_alias_rejection(
            revision,
            parameter_id,
            selected_profile,
            value,
            "alias-public-field-missing",
            "",
            mask,
        );
        return mask | ALIAS_REQUEST_REJECTED;
    }
    let control_range =
        find_surface_particle_alias_range(&profile, parameter_id).unwrap_or([value, value]);
    let clamped_value = clamp_f32(value, control_range[0], control_range[1]);
    if apply_surface_particle_public_field(&public_field, clamped_value) {
        mask |= ALIAS_PUBLIC_FIELD_APPLIED;
    }
    let derived_write_count = apply_surface_particle_derived_alias_writes(alias, clamped_value);
    if derived_write_count > 0 {
        mask |= ALIAS_DERIVED_FIELD_APPLIED;
    }
    log_surface_particle_alias_acceptance(
        revision,
        parameter_id,
        selected_profile,
        &public_field,
        &json_string(alias.get("runtime_marker")),
        alias_status,
        value,
        clamped_value,
        derived_write_count,
        mask,
    );
    mask | ALIAS_REQUEST_ACCEPTED
}

fn apply_surface_particle_public_field(public_field: &str, value: f32) -> bool {
    match public_field {
        "driver0_value01" => {
            SURFACE_PARTICLE_DRIVER0_BITS.store(value.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
            true
        }
        "driver1_value01" => {
            SURFACE_PARTICLE_DRIVER1_BITS.store(value.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
            true
        }
        "driver2_value01" => {
            SURFACE_PARTICLE_DRIVER2_BITS.store(value.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
            true
        }
        "driver3_value01" => {
            SURFACE_PARTICLE_DRIVER3_BITS.store(value.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
            true
        }
        "driver4_value01" => {
            SURFACE_PARTICLE_DRIVER4_BITS.store(value.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
            true
        }
        "driver5_value01" => {
            SURFACE_PARTICLE_DRIVER5_BITS.store(value.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
            true
        }
        "driver6_value01" => {
            SURFACE_PARTICLE_DRIVER6_BITS.store(value.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
            true
        }
        "driver7_value01" => {
            SURFACE_PARTICLE_DRIVER7_BITS.store(value.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
            true
        }
        "point_scale" => {
            SURFACE_PARTICLE_POINT_SCALE_BITS
                .store(value.clamp(0.35, 2.25).to_bits(), Ordering::Relaxed);
            true
        }
        "tracer_draw_slots_per_oscillator" => {
            SURFACE_PARTICLE_TRACER_DRAW_SLOTS_BITS
                .store(value.clamp(0.0, 7.0).to_bits(), Ordering::Relaxed);
            true
        }
        "tracer_lifetime_seconds" => {
            SURFACE_PARTICLE_TRACER_LIFETIME_SECONDS_BITS
                .store(value.clamp(0.0, 0.5).to_bits(), Ordering::Relaxed);
            true
        }
        "tracer_copies_per_second" => {
            SURFACE_PARTICLE_TRACER_COPIES_PER_SECOND_BITS
                .store(value.clamp(0.0, 14.0).to_bits(), Ordering::Relaxed);
            true
        }
        "transparency_opacity" => {
            SURFACE_PARTICLE_TRANSPARENCY_OPACITY_BITS
                .store(value.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
            true
        }
        "projection_world_scale" => {
            SURFACE_PARTICLE_PROJECTION_WORLD_SCALE_BITS
                .store(value.clamp(0.5, 2.0).to_bits(), Ordering::Relaxed);
            true
        }
        _ => false,
    }
}

fn apply_surface_particle_derived_alias_writes(
    alias: &serde_json::Value,
    source_value: f32,
) -> usize {
    let Some(writes) = alias
        .get("derived_writes")
        .and_then(serde_json::Value::as_array)
    else {
        return 0;
    };
    let mut applied = 0_usize;
    for write in writes {
        let public_field = json_string(write.get("public_field"));
        if public_field.is_empty() {
            continue;
        }
        let Some(source_range) = json_f32_pair(write.get("source_range")) else {
            continue;
        };
        let Some(target_range) = json_f32_pair(write.get("range")) else {
            continue;
        };
        let denominator = source_range[1] - source_range[0];
        let t = if denominator.abs() <= f32::EPSILON {
            0.0
        } else {
            ((source_value - source_range[0]) / denominator).clamp(0.0, 1.0)
        };
        let target_value = target_range[0] + (target_range[1] - target_range[0]) * t;
        if apply_surface_particle_public_field(&public_field, target_value) {
            applied += 1;
        }
    }
    applied
}

fn find_surface_particle_alias_range(
    profile: &serde_json::Value,
    parameter_id: &str,
) -> Option<[f32; 2]> {
    for key in ["live_ui_controls", "carrier_controls"] {
        let Some(controls) = profile.get(key).and_then(serde_json::Value::as_array) else {
            continue;
        };
        for control in controls {
            if json_string(control.get("parameter_id")) == parameter_id {
                return json_f32_pair(control.get("range"));
            }
        }
    }
    None
}

fn find_alias_by_parameter_id<'a>(
    aliases: Option<&'a serde_json::Value>,
    parameter_id: &str,
) -> Option<&'a serde_json::Value> {
    aliases?
        .as_array()?
        .iter()
        .find(|alias| json_string(alias.get("parameter_id")) == parameter_id)
}

fn json_string(value: Option<&serde_json::Value>) -> String {
    value
        .and_then(serde_json::Value::as_str)
        .unwrap_or("")
        .to_string()
}

fn json_string_array_contains(value: Option<&serde_json::Value>, expected: &str) -> bool {
    value
        .and_then(serde_json::Value::as_array)
        .map(|items| {
            items
                .iter()
                .filter_map(serde_json::Value::as_str)
                .any(|item| item == expected)
        })
        .unwrap_or(false)
}

fn json_f32_pair(value: Option<&serde_json::Value>) -> Option<[f32; 2]> {
    let array = value?.as_array()?;
    if array.len() != 2 {
        return None;
    }
    Some([array[0].as_f64()? as f32, array[1].as_f64()? as f32])
}

fn log_surface_particle_alias_acceptance(
    revision: u32,
    parameter_id: &str,
    selected_profile: &str,
    public_field: &str,
    runtime_marker: &str,
    alias_status: &str,
    requested_value: f32,
    accepted_value: f32,
    derived_write_count: usize,
    mask: i64,
) {
    let alias_build_fields = surface_particle_private_alias_compact_marker_fields();
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=alias-parameter-updated-compact privateSurfaceParticleUiParameterPacketReady=true {} privateSurfaceParticleUiParameterRejected=false privateSurfaceParticleUiParameterRejectReason=none privateSurfaceParticleUiParameterRequestRevision={} privateSurfaceParticleUiParameterAcceptRevision={} privateSurfaceParticleUiParameterAcceptedAlias={} privateSurfaceParticleUiParameterAliasPublicField={} privateSurfaceParticleUiParameterAliasRuntimeMarker={} privateSurfaceParticleUiParameterAliasStatus={} privateSurfaceParticleUiParameterAliasActivationProfile={} privateSurfaceParticleUiParameterAliasDerivedWriteCount={} privateSurfaceParticleUiParameterAliasRequestValue={:.3} privateSurfaceParticleUiParameterAliasAcceptedValue={:.3} privateSurfaceParticleUiParameterAliasMask={}",
            alias_build_fields,
            revision,
            revision,
            marker_token(parameter_id),
            marker_token(public_field),
            marker_token(runtime_marker),
            marker_token(alias_status),
            marker_token(selected_profile),
            derived_write_count,
            requested_value,
            accepted_value,
            mask | ALIAS_REQUEST_ACCEPTED,
        ),
    );
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=alias-parameter-updated renderPolicy=native-vulkan-wsi-surface-panel transport=jni-live-queue computeParameterBridge=true privateSurfaceParticleUiParameterPacketReady=true privateSurfaceParticleUiParameterTransport=jni-live-queue privateSurfaceParticleUiParameterHighRatePayloadAllowed=false {} privateSurfaceParticleUiParameterRejected=false privateSurfaceParticleUiParameterRejectReason=none privateSurfaceParticleUiParameterRequestRevision={} privateSurfaceParticleUiParameterAcceptRevision={} privateSurfaceParticleUiParameterAcceptedAlias={} privateSurfaceParticleUiParameterAliasPublicField={} privateSurfaceParticleUiParameterAliasRuntimeMarker={} privateSurfaceParticleUiParameterAliasStatus={} privateSurfaceParticleUiParameterAliasActivationProfile={} privateSurfaceParticleUiParameterAliasDerivedWriteCount={} privateSurfaceParticleUiParameterAliasRequestValue={:.3} privateSurfaceParticleUiParameterAliasAcceptedValue={:.3} privateSurfaceParticleUiParameterAliasMask={} {}",
            alias_build_fields,
            revision,
            revision,
            marker_token(parameter_id),
            marker_token(public_field),
            marker_token(runtime_marker),
            marker_token(alias_status),
            marker_token(selected_profile),
            derived_write_count,
            requested_value,
            accepted_value,
            mask | ALIAS_REQUEST_ACCEPTED,
            current_surface_particle_renderer_mode().marker_fields(),
        ),
    );
}

fn log_surface_particle_alias_rejection(
    revision: u32,
    parameter_id: &str,
    selected_profile: &str,
    requested_value: f32,
    reason: &str,
    required_activation_profile: &str,
    mask: i64,
) {
    let alias_build_fields = surface_particle_private_alias_compact_marker_fields();
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=alias-parameter-rejected-compact privateSurfaceParticleUiParameterPacketReady=true {} privateSurfaceParticleUiParameterRejected=true privateSurfaceParticleUiParameterRejectReason={} privateSurfaceParticleUiParameterRequestRevision={} privateSurfaceParticleUiParameterRejectedAlias={} privateSurfaceParticleUiParameterRequiredActivationProfile={} privateSurfaceParticleUiParameterAliasActivationProfile={} privateSurfaceParticleUiParameterAliasRequestValue={:.3} privateSurfaceParticleUiParameterAliasMask={}",
            alias_build_fields,
            marker_token(reason),
            revision,
            marker_token(parameter_id),
            marker_token(required_activation_profile),
            marker_token(selected_profile),
            requested_value,
            mask | ALIAS_REQUEST_REJECTED,
        ),
    );
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=alias-parameter-rejected renderPolicy=native-vulkan-wsi-surface-panel transport=jni-live-queue computeParameterBridge=true privateSurfaceParticleUiParameterPacketReady=true privateSurfaceParticleUiParameterTransport=jni-live-queue privateSurfaceParticleUiParameterHighRatePayloadAllowed=false {} privateSurfaceParticleUiParameterRejected=true privateSurfaceParticleUiParameterRejectReason={} privateSurfaceParticleUiParameterRequestRevision={} privateSurfaceParticleUiParameterRejectedAlias={} privateSurfaceParticleUiParameterRequiredActivationProfile={} privateSurfaceParticleUiParameterAliasActivationProfile={} privateSurfaceParticleUiParameterAliasRequestValue={:.3} privateSurfaceParticleUiParameterAliasMask={} {}",
            alias_build_fields,
            marker_token(reason),
            revision,
            marker_token(parameter_id),
            marker_token(required_activation_profile),
            marker_token(selected_profile),
            requested_value,
            mask | ALIAS_REQUEST_REJECTED,
            current_surface_particle_renderer_mode().marker_fields(),
        ),
    );
}

#[cfg(target_os = "android")]
fn jstring_to_string(env: *mut jni::sys::JNIEnv, value: jni::sys::jstring) -> String {
    use jni::objects::JString;

    if env.is_null() || value.is_null() {
        return String::new();
    }
    let mut env = match unsafe { jni::JNIEnv::from_raw(env) } {
        Ok(env) => env,
        Err(_) => return String::new(),
    };
    let value = unsafe { JString::from_raw(value) };
    env.get_string(&value)
        .map(|text| text.to_string_lossy().into_owned())
        .unwrap_or_default()
}

#[no_mangle]
#[allow(non_snake_case)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdateSurfaceParticlePanelPose(
    _env: *mut c_void,
    _thiz: *mut c_void,
    center_x: c_float,
    center_y: c_float,
    center_z: c_float,
    right_x: c_float,
    right_y: c_float,
    right_z: c_float,
    up_x: c_float,
    up_y: c_float,
    up_z: c_float,
    width_meters: c_float,
    height_meters: c_float,
    target_distance_meters: c_float,
    left_eye_offset_right_meters: c_float,
    right_eye_offset_right_meters: c_float,
) -> i64 {
    let projection = ReplayHandPanelProjection {
        center: [
            finite_or(center_x, 0.0),
            finite_or(center_y, 1.22),
            finite_or(center_z, -0.72),
        ],
        right: normalize_or(
            [
                finite_or(right_x, 1.0),
                finite_or(right_y, 0.0),
                finite_or(right_z, 0.0),
            ],
            [1.0, 0.0, 0.0],
        ),
        up: normalize_or(
            [
                finite_or(up_x, 0.0),
                finite_or(up_y, 1.0),
                finite_or(up_z, 0.0),
            ],
            [0.0, 1.0, 0.0],
        ),
        width_meters: finite_or(width_meters, 1.44).clamp(0.20, 5.4),
        height_meters: finite_or(height_meters, 1.44).clamp(0.20, 4.0),
        target_distance_meters: finite_or(target_distance_meters, 2.00).clamp(0.20, 2.00),
        left_eye_offset_right_meters: finite_or(
            left_eye_offset_right_meters,
            DEFAULT_SURFACE_PARTICLE_LEFT_EYE_OFFSET_RIGHT_METERS,
        )
        .clamp(-0.12, 0.12),
        right_eye_offset_right_meters: finite_or(
            right_eye_offset_right_meters,
            DEFAULT_SURFACE_PARTICLE_RIGHT_EYE_OFFSET_RIGHT_METERS,
        )
        .clamp(-0.12, 0.12),
        valid: true,
    };
    store_surface_particle_panel_projection(projection);
    capture_surface_particle_world_anchor_if_needed(projection);
    store_live_hand_panel_basis(
        projection.center,
        projection.right,
        projection.up,
        projection.target_distance_meters,
        projection.valid,
    );
    log_surface_particle_scene_fixed_world_anchor_sample_if_captured(projection);
    let update_count = SURFACE_PARTICLE_PANEL_POSE_LOG_COUNT.fetch_add(1, Ordering::Relaxed);
    if update_count < 4 {
        android_log_info(
            "RQSpatialCameraPanelNative",
            &format!(
                "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=panel-pose-updated renderPolicy=native-vulkan-wsi-surface-panel panelPoseNativeUpdate=true carrierSurfaceProjection=spatial-sdk-panel-plane-basis drawProjection=spatial-sdk-world-explicit-viewer-camera-to-panel-plane particleLayerTargetDistanceParameterSource=runtime-hotload-android-property particleLayerEyeOffsetSource=Scene.getEyeOffsets.viewerLocalX panelCenterM={:.4};{:.4};{:.4} panelRight={:.4};{:.4};{:.4} panelUp={:.4};{:.4};{:.4} panelWidthMeters={:.3} panelHeightMeters={:.3} panelTargetDistanceMeters={:.3} leftEyeOffsetRightMeters={:.4} rightEyeOffsetRightMeters={:.4}",
                projection.center[0],
                projection.center[1],
                projection.center[2],
                projection.right[0],
                projection.right[1],
                projection.right[2],
                projection.up[0],
                projection.up[1],
                projection.up[2],
                projection.width_meters,
                projection.height_meters,
                projection.target_distance_meters,
                projection.left_eye_offset_right_meters,
                projection.right_eye_offset_right_meters,
            ),
        );
    }
    0b1_1111
}

#[no_mangle]
#[allow(non_snake_case)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdateSurfaceParticleViewerEyePose(
    _env: *mut c_void,
    _thiz: *mut c_void,
    viewer_x: c_float,
    viewer_y: c_float,
    viewer_z: c_float,
    viewer_right_x: c_float,
    viewer_right_y: c_float,
    viewer_right_z: c_float,
    viewer_up_x: c_float,
    viewer_up_y: c_float,
    viewer_up_z: c_float,
    viewer_forward_x: c_float,
    viewer_forward_y: c_float,
    viewer_forward_z: c_float,
    left_eye_x: c_float,
    left_eye_y: c_float,
    left_eye_z: c_float,
    right_eye_x: c_float,
    right_eye_y: c_float,
    right_eye_z: c_float,
) -> i64 {
    let viewer = [
        finite_or(viewer_x, 0.0),
        finite_or(viewer_y, 1.4),
        finite_or(viewer_z, 0.0),
    ];
    let viewer_right = normalize_or(
        [
            finite_or(viewer_right_x, 1.0),
            finite_or(viewer_right_y, 0.0),
            finite_or(viewer_right_z, 0.0),
        ],
        [1.0, 0.0, 0.0],
    );
    let viewer_up = normalize_or(
        [
            finite_or(viewer_up_x, 0.0),
            finite_or(viewer_up_y, 1.0),
            finite_or(viewer_up_z, 0.0),
        ],
        [0.0, 1.0, 0.0],
    );
    let viewer_forward = normalize_or(
        [
            finite_or(viewer_forward_x, 0.0),
            finite_or(viewer_forward_y, 0.0),
            finite_or(viewer_forward_z, -1.0),
        ],
        [0.0, 0.0, -1.0],
    );
    let left_eye = [
        finite_or(left_eye_x, viewer[0]),
        finite_or(left_eye_y, viewer[1]),
        finite_or(left_eye_z, viewer[2]),
    ];
    let right_eye = [
        finite_or(right_eye_x, viewer[0]),
        finite_or(right_eye_y, viewer[1]),
        finite_or(right_eye_z, viewer[2]),
    ];

    store_surface_particle_viewer_eye_pose(
        viewer,
        viewer_right,
        viewer_up,
        viewer_forward,
        left_eye,
        right_eye,
    );
    maybe_auto_recenter_surface_particle_world_anchor_on_viewer(viewer);

    let update_count = SURFACE_PARTICLE_VIEWER_EYE_POSE_LOG_COUNT.fetch_add(1, Ordering::Relaxed);
    if update_count < 4 {
        android_log_info(
            "RQSpatialCameraPanelNative",
            &format!(
                "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=viewer-eye-pose-updated renderPolicy=native-vulkan-wsi-surface-panel privateSurfaceParticleEyePoseSource=Scene.getViewerPose+Scene.getEyeOffsets privateSurfaceParticlePanelDefinesEye=false privateSurfaceParticleOpenXrViewDrawAuthority=false viewerWorldM={:.4};{:.4};{:.4} viewerRight={:.4};{:.4};{:.4} viewerUp={:.4};{:.4};{:.4} viewerForward={:.4};{:.4};{:.4} leftEyeWorldM={:.4};{:.4};{:.4} rightEyeWorldM={:.4};{:.4};{:.4} explicitEyePoseValid={}",
                viewer[0],
                viewer[1],
                viewer[2],
                viewer_right[0],
                viewer_right[1],
                viewer_right[2],
                viewer_up[0],
                viewer_up[1],
                viewer_up[2],
                viewer_forward[0],
                viewer_forward[1],
                viewer_forward[2],
                left_eye[0],
                left_eye[1],
                left_eye[2],
                right_eye[0],
                right_eye[1],
                right_eye[2],
                bool_token(true),
            ),
        );
    }
    0b1111
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeRecenterSurfaceParticleSphereOnViewer(
    _env: *mut c_void,
    _thiz: *mut c_void,
) -> i64 {
    let mut mask = RECENTER_REQUEST_RECEIVED;
    let viewer_pose_valid = SURFACE_PARTICLE_VIEWER_EYE_POSE_VALID.load(Ordering::Relaxed);
    if viewer_pose_valid {
        mask |= RECENTER_VIEWER_POSE_VALID;
    }
    let Some(viewer) = current_surface_particle_viewer_world_position() else {
        android_log_info(
            "RQSpatialCameraPanelNative",
            &format!(
                "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=private-world-anchor-recenter-rejected renderPolicy=native-vulkan-wsi-surface-panel privateSurfaceParticleWorldAnchorMode=spatial-sdk-fixed-sim-world-transform privateSurfaceParticleWorldAnchorRecenterSource=spatial-sdk-viewer-trigger privateSurfaceParticleWorldAnchorRecenterRejected=true privateSurfaceParticleWorldAnchorRecenterRejectReason={} privateSurfaceParticleWorldAnchorCenterSource=current-spatial-sdk-viewer-world-coordinate privateSurfaceParticleWorldAnchorBasis=spatial-world-canonical-axes privateSurfaceParticleWorldAnchorScaleSource=fixed-sim-meter-radius privateSurfaceParticleSimRegistration=sim-space-fixed-in-spatial-sdk-world-space privateSurfaceParticleSimTransform=spatial-world-from-sim-fixed-configured-origin-basis-meter-scale privateSurfaceParticleSimWorldAxesStable=true privateSurfaceParticleRecenterChangesCoordinateMapping=false recenterMask={}",
                if viewer_pose_valid {
                    "viewer-position-non-finite"
                } else {
                    "viewer-pose-not-yet-valid"
                },
                mask,
            ),
        );
        return mask;
    };
    mask |= RECENTER_VIEWER_POSITION_FINITE;
    let (right, up, forward) = store_surface_particle_world_anchor_canonical(viewer);
    SURFACE_PARTICLE_AUTO_RECENTER_LOCKED.store(true, Ordering::Relaxed);
    mask |= RECENTER_WORLD_CENTER_STORED
        | RECENTER_CANONICAL_BASIS_STORED
        | RECENTER_WORLD_ANCHOR_VALID;
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=private-world-anchor-recentered renderPolicy=native-vulkan-wsi-surface-panel privateSurfaceParticleWorldAnchorMode=spatial-sdk-fixed-sim-world-transform privateSurfaceParticleWorldAnchorRecenterSource=spatial-sdk-viewer-trigger privateSurfaceParticleWorldAnchorRecenterAccepted=true privateSurfaceParticleWorldAnchorRecenterRejected=false privateSurfaceParticleWorldAnchorCenterSource=current-spatial-sdk-viewer-world-coordinate privateSurfaceParticleWorldAnchorBasis=spatial-world-canonical-axes privateSurfaceParticleWorldAnchorScaleSource=fixed-sim-meter-radius privateSurfaceParticleSimRegistration=sim-space-fixed-in-spatial-sdk-world-space privateSurfaceParticleSimTransform=spatial-world-from-sim-fixed-configured-origin-basis-meter-scale privateSurfaceParticleSimWorldAxesStable=true privateSurfaceParticleRecenterChangesCoordinateMapping=false privateSurfaceParticleRecenterChangesOnlySphereCenter=true worldAnchorCenterM={:.4};{:.4};{:.4} viewerWorldM={:.4};{:.4};{:.4} worldAnchorRight={:.4};{:.4};{:.4} worldAnchorUp={:.4};{:.4};{:.4} worldAnchorForward={:.4};{:.4};{:.4} recenterMask={}",
            viewer[0],
            viewer[1],
            viewer[2],
            viewer[0],
            viewer[1],
            viewer[2],
            right[0],
            right[1],
            right[2],
            up[0],
            up[1],
            up[2],
            forward[0],
            forward[1],
            forward[2],
            mask,
        ),
    );
    mask
}

fn log_start_receipt(mask: i64, status: &str) {
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status={} renderPolicy=native-vulkan-wsi-surface-panel {} {} startMask={} surfaceNonNull={} nativeWindowObtained={} renderThreadSpawned={}",
            marker_token(status),
            current_surface_particle_renderer_mode().marker_fields(),
            surface_particle_private_build_marker_fields(),
            mask,
            bool_token((mask & START_SURFACE_NON_NULL) != 0),
            bool_token((mask & START_NATIVE_WINDOW_OBTAINED) != 0),
            bool_token((mask & START_RENDER_THREAD_SPAWNED) != 0),
        ),
    );
}

unsafe fn create_external_openxr_swapchain(
    openxr_instance_handle: i64,
    openxr_session_handle: i64,
    openxr_get_instance_proc_addr_handle: i64,
    width: u32,
    height: u32,
) -> Result<u64, String> {
    if openxr_instance_handle == 0
        || openxr_session_handle == 0
        || openxr_get_instance_proc_addr_handle == 0
    {
        return Err("missing-openxr-handles".to_string());
    }

    let instance = openxr_sys::Instance::from_raw(openxr_instance_handle as u64);
    let session = openxr_sys::Session::from_raw(openxr_session_handle as u64);
    let get_instance_proc_addr: openxr_sys::pfn::GetInstanceProcAddr =
        mem::transmute(openxr_get_instance_proc_addr_handle as usize);

    let enumerate_formats_resolution = resolve_external_swapchain_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrEnumerateSwapchainFormats",
    );
    let create_swapchain_resolution = resolve_external_swapchain_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrCreateSwapchain",
    );
    let enumerate_images_resolution = resolve_external_swapchain_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrEnumerateSwapchainImages",
    );
    let acquire_image_resolution = resolve_external_swapchain_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrAcquireSwapchainImage",
    );
    let wait_image_resolution = resolve_external_swapchain_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrWaitSwapchainImage",
    );
    let release_image_resolution = resolve_external_swapchain_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrReleaseSwapchainImage",
    );

    let all_required_resolved = enumerate_formats_resolution.resolved
        && create_swapchain_resolution.resolved
        && enumerate_images_resolution.resolved
        && acquire_image_resolution.resolved
        && wait_image_resolution.resolved
        && release_image_resolution.resolved;
    if !all_required_resolved {
        android_log_info(
            "RQSpatialCameraPanelNative",
            &format!(
                "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=external-xr-swapchain-wrap-probe status=native-functions-unavailable externalSwapchainProbe=true xrEnumerateSwapchainFormatsResolved={} xrCreateSwapchainResolved={} xrEnumerateSwapchainImagesResolved={} xrAcquireSwapchainImageResolved={} xrWaitSwapchainImageResolved={} xrReleaseSwapchainImageResolved={} xrEnumerateSwapchainFormatsResult={} xrCreateSwapchainResult={} xrEnumerateSwapchainImagesResult={} xrAcquireSwapchainImageResult={} xrWaitSwapchainImageResult={} xrReleaseSwapchainImageResult={} nativeFrameLoop=false runtimeCrash=false",
                bool_token(enumerate_formats_resolution.resolved),
                bool_token(create_swapchain_resolution.resolved),
                bool_token(enumerate_images_resolution.resolved),
                bool_token(acquire_image_resolution.resolved),
                bool_token(wait_image_resolution.resolved),
                bool_token(release_image_resolution.resolved),
                enumerate_formats_resolution.result,
                create_swapchain_resolution.result,
                enumerate_images_resolution.result,
                acquire_image_resolution.result,
                wait_image_resolution.result,
                release_image_resolution.result,
            ),
        );
        return Err("required-openxr-functions-unavailable".to_string());
    }

    let enumerate_swapchain_formats: openxr_sys::pfn::EnumerateSwapchainFormats =
        external_swapchain_typed_function(enumerate_formats_resolution.function)
            .expect("resolved xrEnumerateSwapchainFormats");
    let create_swapchain: openxr_sys::pfn::CreateSwapchain =
        external_swapchain_typed_function(create_swapchain_resolution.function)
            .expect("resolved xrCreateSwapchain");
    let enumerate_swapchain_images: openxr_sys::pfn::EnumerateSwapchainImages =
        external_swapchain_typed_function(enumerate_images_resolution.function)
            .expect("resolved xrEnumerateSwapchainImages");
    let acquire_swapchain_image: openxr_sys::pfn::AcquireSwapchainImage =
        external_swapchain_typed_function(acquire_image_resolution.function)
            .expect("resolved xrAcquireSwapchainImage");
    let wait_swapchain_image: openxr_sys::pfn::WaitSwapchainImage =
        external_swapchain_typed_function(wait_image_resolution.function)
            .expect("resolved xrWaitSwapchainImage");
    let release_swapchain_image: openxr_sys::pfn::ReleaseSwapchainImage =
        external_swapchain_typed_function(release_image_resolution.function)
            .expect("resolved xrReleaseSwapchainImage");

    let mut format_count = 0u32;
    let enumerate_count_result =
        enumerate_swapchain_formats(session, 0, &mut format_count, ptr::null_mut());
    if enumerate_count_result != openxr_sys::Result::SUCCESS || format_count == 0 {
        return Err(format!(
            "format-enumerate-{}-count-{}",
            external_swapchain_xr_result_token(enumerate_count_result),
            format_count
        ));
    }

    let mut formats = vec![0i64; format_count as usize];
    let enumerate_formats_result = enumerate_swapchain_formats(
        session,
        format_count,
        &mut format_count,
        formats.as_mut_ptr(),
    );
    if enumerate_formats_result != openxr_sys::Result::SUCCESS {
        return Err(format!(
            "format-list-{}",
            external_swapchain_xr_result_token(enumerate_formats_result)
        ));
    }
    formats.truncate(format_count as usize);
    let preferred_formats = [
        vk::Format::R8G8B8A8_UNORM.as_raw() as i64,
        vk::Format::R8G8B8A8_SRGB.as_raw() as i64,
        vk::Format::B8G8R8A8_UNORM.as_raw() as i64,
        vk::Format::B8G8R8A8_SRGB.as_raw() as i64,
    ];
    let selected_format = preferred_formats
        .iter()
        .copied()
        .find(|preferred| formats.contains(preferred))
        .unwrap_or(formats[0]);

    let usage_with_sampled = openxr_sys::SwapchainUsageFlags::COLOR_ATTACHMENT
        | openxr_sys::SwapchainUsageFlags::SAMPLED;
    let mut usage_flags = usage_with_sampled;
    let mut swapchain = openxr_sys::Swapchain::NULL;
    let mut create_info = openxr_sys::SwapchainCreateInfo {
        ty: openxr_sys::SwapchainCreateInfo::TYPE,
        next: ptr::null(),
        create_flags: openxr_sys::SwapchainCreateFlags::EMPTY,
        usage_flags,
        format: selected_format,
        sample_count: 1,
        width,
        height,
        face_count: 1,
        array_size: 1,
        mip_count: 1,
    };
    let mut create_result = create_swapchain(session, &create_info, &mut swapchain);
    let mut usage_label = "color_attachment_sampled";
    if create_result != openxr_sys::Result::SUCCESS {
        usage_flags = openxr_sys::SwapchainUsageFlags::COLOR_ATTACHMENT;
        usage_label = "color_attachment";
        create_info.usage_flags = usage_flags;
        swapchain = openxr_sys::Swapchain::NULL;
        create_result = create_swapchain(session, &create_info, &mut swapchain);
    }
    if create_result != openxr_sys::Result::SUCCESS || swapchain == openxr_sys::Swapchain::NULL {
        return Err(format!(
            "xrCreateSwapchain-{}-usage-{}",
            external_swapchain_xr_result_token(create_result),
            usage_label
        ));
    }

    let mut image_count = 0u32;
    let enumerate_image_count_result =
        enumerate_swapchain_images(swapchain, 0, &mut image_count, ptr::null_mut());
    let mut enumerate_image_result = enumerate_image_count_result;
    let mut first_image_handle = 0u64;
    if enumerate_image_count_result == openxr_sys::Result::SUCCESS && image_count > 0 {
        let mut images = vec![
            openxr_sys::SwapchainImageVulkanKHR {
                ty: openxr_sys::SwapchainImageVulkanKHR::TYPE,
                next: ptr::null_mut(),
                image: 0,
            };
            image_count as usize
        ];
        enumerate_image_result = enumerate_swapchain_images(
            swapchain,
            image_count,
            &mut image_count,
            images
                .as_mut_ptr()
                .cast::<openxr_sys::SwapchainImageBaseHeader>(),
        );
        if enumerate_image_result == openxr_sys::Result::SUCCESS {
            first_image_handle = images.first().map(|image| image.image).unwrap_or(0);
        }
    }

    let acquire_info = openxr_sys::SwapchainImageAcquireInfo {
        ty: openxr_sys::SwapchainImageAcquireInfo::TYPE,
        next: ptr::null(),
    };
    let mut acquired_index = 0u32;
    let acquire_result = acquire_swapchain_image(swapchain, &acquire_info, &mut acquired_index);
    let mut wait_result_token = "not-run".to_string();
    let mut release_result_token = "not-run".to_string();
    if acquire_result == openxr_sys::Result::SUCCESS {
        let wait_info = openxr_sys::SwapchainImageWaitInfo {
            ty: openxr_sys::SwapchainImageWaitInfo::TYPE,
            next: ptr::null(),
            timeout: openxr_sys::Duration::from_nanos(100_000_000),
        };
        let wait_result = wait_swapchain_image(swapchain, &wait_info);
        wait_result_token = external_swapchain_xr_result_token(wait_result);
        let release_info = openxr_sys::SwapchainImageReleaseInfo {
            ty: openxr_sys::SwapchainImageReleaseInfo::TYPE,
            next: ptr::null(),
        };
        let release_result = release_swapchain_image(swapchain, &release_info);
        release_result_token = external_swapchain_xr_result_token(release_result);
    }

    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=external-xr-swapchain-wrap-probe status=native-create-result externalSwapchainProbe=true xrCreateSwapchainResult={} swapchainHandle={} widthPx={} heightPx={} arraySize=1 mipCount=1 selectedFormat={} enumeratedFormatCount={} usageFlags={} usageFlagsRaw={} xrEnumerateSwapchainImagesResult={} swapchainImagesEnumerated={} firstVkImageHandle={} xrAcquireSwapchainImageResult={} acquiredImageIndex={} xrWaitSwapchainImageResult={} xrReleaseSwapchainImageResult={} nativeCanRenderIntoImages=false renderBlockReason=missing-spatial-sdk-vulkan-device-queue visiblePatternConfirmed=false nativeFrameLoop=false deviceLost=false runtimeCrash=false",
            external_swapchain_xr_result_token(create_result),
            swapchain.into_raw(),
            width,
            height,
            selected_format,
            formats.len(),
            usage_label,
            usage_flags.into_raw(),
            external_swapchain_xr_result_token(enumerate_image_result),
            image_count,
            first_image_handle,
            external_swapchain_xr_result_token(acquire_result),
            acquired_index,
            wait_result_token,
            release_result_token,
        ),
    );

    Ok(swapchain.into_raw())
}

unsafe fn destroy_external_openxr_swapchain(
    openxr_instance_handle: i64,
    openxr_get_instance_proc_addr_handle: i64,
    swapchain_handle: i64,
) -> c_int {
    if openxr_instance_handle == 0
        || openxr_get_instance_proc_addr_handle == 0
        || swapchain_handle == 0
    {
        return openxr_sys::Result::ERROR_HANDLE_INVALID.into_raw();
    }
    let instance = openxr_sys::Instance::from_raw(openxr_instance_handle as u64);
    let get_instance_proc_addr: openxr_sys::pfn::GetInstanceProcAddr =
        mem::transmute(openxr_get_instance_proc_addr_handle as usize);
    let destroy_resolution = resolve_external_swapchain_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrDestroySwapchain",
    );
    if !destroy_resolution.resolved {
        return openxr_sys::Result::ERROR_FUNCTION_UNSUPPORTED.into_raw();
    }
    let destroy_swapchain: openxr_sys::pfn::DestroySwapchain =
        external_swapchain_typed_function(destroy_resolution.function)
            .expect("resolved xrDestroySwapchain");
    let result = destroy_swapchain(openxr_sys::Swapchain::from_raw(swapchain_handle as u64));
    result.into_raw()
}

fn resolve_external_swapchain_openxr_function(
    instance: openxr_sys::Instance,
    get_instance_proc_addr: openxr_sys::pfn::GetInstanceProcAddr,
    name: &str,
) -> ExternalSwapchainOpenXrFunction {
    let function_name = CString::new(name).expect("static OpenXR symbol must not contain NUL");
    let mut function: Option<openxr_sys::pfn::VoidFunction> = None;
    let result = unsafe { get_instance_proc_addr(instance, function_name.as_ptr(), &mut function) };
    ExternalSwapchainOpenXrFunction {
        resolved: result == openxr_sys::Result::SUCCESS && function.is_some(),
        result: external_swapchain_xr_result_token(result),
        function,
    }
}

fn external_swapchain_typed_function<T>(
    function: Option<openxr_sys::pfn::VoidFunction>,
) -> Option<T> {
    function.map(|function| unsafe { mem::transmute_copy(&function) })
}

fn external_swapchain_xr_result_token(result: openxr_sys::Result) -> String {
    if result == openxr_sys::Result::SUCCESS {
        "success".to_string()
    } else {
        format!("code_{}", result.into_raw())
    }
}

struct SurfaceParticleStats {
    frames_presented: u32,
    particle_count: u32,
    extent: vk::Extent2D,
    renderer_mode: SurfaceParticleRendererMode,
}

struct SurfaceParticleFrameTargets {
    image_views: Vec<vk::ImageView>,
    render_pass: vk::RenderPass,
    framebuffers: Vec<vk::Framebuffer>,
    extent: vk::Extent2D,
}

impl SurfaceParticleFrameTargets {
    unsafe fn create(
        device: &ash::Device,
        format: vk::Format,
        extent: vk::Extent2D,
        images: &[vk::Image],
    ) -> Result<Self, String> {
        let image_views = create_image_views(device, format, images)?;
        let render_pass = create_render_pass(device, format)?;
        let framebuffers = create_framebuffers(device, render_pass, extent, &image_views)?;
        Ok(Self {
            image_views,
            render_pass,
            framebuffers,
            extent,
        })
    }

    fn framebuffer(&self, image_index: u32) -> vk::Framebuffer {
        self.framebuffers[image_index as usize]
    }

    unsafe fn destroy(&mut self, device: &ash::Device) {
        for framebuffer in self.framebuffers.drain(..) {
            device.destroy_framebuffer(framebuffer, None);
        }
        if self.render_pass != vk::RenderPass::null() {
            device.destroy_render_pass(self.render_pass, None);
            self.render_pass = vk::RenderPass::null();
        }
        for image_view in self.image_views.drain(..) {
            device.destroy_image_view(image_view, None);
        }
    }
}

#[derive(Clone, Copy)]
struct SurfaceParticleStereoLayout {
    stereo_mode: &'static str,
    eye_count: u32,
    per_eye_extent: vk::Extent2D,
    packed_extent: vk::Extent2D,
}

struct SdkQuadVulkanProbeStats {
    frames_presented: u32,
    extent: vk::Extent2D,
    swapchain_image_count: usize,
}

fn surface_particle_stereo_layout(extent: vk::Extent2D) -> SurfaceParticleStereoLayout {
    let packed_left_right = extent.width >= extent.height.saturating_mul(2);
    let eye_count = if packed_left_right { 2 } else { 1 };
    let per_eye_width = if packed_left_right {
        (extent.width / 2).max(1)
    } else {
        extent.width.max(1)
    };
    SurfaceParticleStereoLayout {
        stereo_mode: if packed_left_right {
            "LeftRight"
        } else {
            "None"
        },
        eye_count,
        per_eye_extent: vk::Extent2D {
            width: per_eye_width,
            height: extent.height.max(1),
        },
        packed_extent: extent,
    }
}

#[derive(Clone, Copy)]
pub(crate) struct SurfaceParticleParameters {
    pub(crate) revision: u32,
    pub(crate) driver0_value01: f32,
    pub(crate) driver1_value01: f32,
    pub(crate) driver2_value01: f32,
    pub(crate) driver3_value01: f32,
    pub(crate) driver4_value01: f32,
    pub(crate) driver5_value01: f32,
    pub(crate) driver6_value01: f32,
    pub(crate) driver7_value01: f32,
    pub(crate) point_scale: f32,
    pub(crate) tracer_draw_slots_per_oscillator: f32,
    pub(crate) tracer_lifetime_seconds: f32,
    pub(crate) tracer_copies_per_second: f32,
    pub(crate) transparency_opacity: f32,
    pub(crate) projection_world_scale: f32,
    pub(crate) live_hand_depth_offset_meters: f32,
    pub(crate) diagnostic_mode: u32,
}

fn current_surface_particle_parameters() -> SurfaceParticleParameters {
    poll_surface_particle_hotload_properties();
    SurfaceParticleParameters {
        revision: SURFACE_PARTICLE_PARAMETER_REVISION.load(Ordering::Relaxed),
        driver0_value01: f32::from_bits(SURFACE_PARTICLE_DRIVER0_BITS.load(Ordering::Relaxed)),
        driver1_value01: f32::from_bits(SURFACE_PARTICLE_DRIVER1_BITS.load(Ordering::Relaxed)),
        driver2_value01: f32::from_bits(SURFACE_PARTICLE_DRIVER2_BITS.load(Ordering::Relaxed)),
        driver3_value01: f32::from_bits(SURFACE_PARTICLE_DRIVER3_BITS.load(Ordering::Relaxed)),
        driver4_value01: f32::from_bits(SURFACE_PARTICLE_DRIVER4_BITS.load(Ordering::Relaxed)),
        driver5_value01: f32::from_bits(SURFACE_PARTICLE_DRIVER5_BITS.load(Ordering::Relaxed)),
        driver6_value01: f32::from_bits(SURFACE_PARTICLE_DRIVER6_BITS.load(Ordering::Relaxed)),
        driver7_value01: f32::from_bits(SURFACE_PARTICLE_DRIVER7_BITS.load(Ordering::Relaxed)),
        point_scale: f32::from_bits(SURFACE_PARTICLE_POINT_SCALE_BITS.load(Ordering::Relaxed)),
        tracer_draw_slots_per_oscillator: f32::from_bits(
            SURFACE_PARTICLE_TRACER_DRAW_SLOTS_BITS.load(Ordering::Relaxed),
        ),
        tracer_lifetime_seconds: f32::from_bits(
            SURFACE_PARTICLE_TRACER_LIFETIME_SECONDS_BITS.load(Ordering::Relaxed),
        ),
        tracer_copies_per_second: f32::from_bits(
            SURFACE_PARTICLE_TRACER_COPIES_PER_SECOND_BITS.load(Ordering::Relaxed),
        ),
        transparency_opacity: f32::from_bits(
            SURFACE_PARTICLE_TRANSPARENCY_OPACITY_BITS.load(Ordering::Relaxed),
        ),
        projection_world_scale: f32::from_bits(
            SURFACE_PARTICLE_PROJECTION_WORLD_SCALE_BITS.load(Ordering::Relaxed),
        ),
        live_hand_depth_offset_meters: f32::from_bits(
            SURFACE_PARTICLE_LIVE_HAND_DEPTH_OFFSET_BITS.load(Ordering::Relaxed),
        ),
        diagnostic_mode: SURFACE_PARTICLE_DIAGNOSTIC_MODE.load(Ordering::Relaxed),
    }
}

fn poll_surface_particle_hotload_properties() {
    poll_surface_particle_diagnostic_mode_property();
    let Some(raw_value) = android_system_property(SURFACE_PARTICLE_LIVE_HAND_DEPTH_OFFSET_PROPERTY)
    else {
        return;
    };
    let Ok(parsed_value) = raw_value.parse::<f32>() else {
        return;
    };
    if !parsed_value.is_finite() {
        return;
    }
    let clamped_value = parsed_value.clamp(-1.5, 1.5);
    let old_bits = SURFACE_PARTICLE_LIVE_HAND_DEPTH_OFFSET_BITS
        .swap(clamped_value.to_bits(), Ordering::Relaxed);
    if old_bits != clamped_value.to_bits() {
        let log_count = SURFACE_PARTICLE_HOTLOAD_LOG_COUNT.fetch_add(1, Ordering::Relaxed);
        if log_count < 8 {
            android_log_info(
                "RQSpatialCameraPanelNative",
                &format!(
                    "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=hotload-parameters-updated renderPolicy=native-vulkan-wsi-surface-panel transport=android-system-property liveHandDepthOffsetParameterSource=runtime-hotload-android-property liveHandDepthOffsetProperty={} liveHandDepthOffsetMeters={:.3}",
                    SURFACE_PARTICLE_LIVE_HAND_DEPTH_OFFSET_PROPERTY,
                    clamped_value,
                ),
            );
        }
    }
}

fn poll_surface_particle_diagnostic_mode_property() {
    let Some(raw_value) = android_system_property(SURFACE_PARTICLE_DIAGNOSTIC_MODE_PROPERTY) else {
        return;
    };
    let Some(mode) = parse_surface_particle_diagnostic_mode(&raw_value) else {
        return;
    };
    let old = SURFACE_PARTICLE_DIAGNOSTIC_MODE.swap(mode, Ordering::Relaxed);
    if old != mode {
        let log_count = SURFACE_PARTICLE_HOTLOAD_LOG_COUNT.fetch_add(1, Ordering::Relaxed);
        if log_count < 8 {
            android_log_info(
                "RQSpatialCameraPanelNative",
                &format!(
                    "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=hotload-parameters-updated renderPolicy=native-vulkan-wsi-surface-panel transport=android-system-property particleDiagnosticModeProperty={} particleDiagnosticMode={} particleDiagnosticModeName={}",
                    SURFACE_PARTICLE_DIAGNOSTIC_MODE_PROPERTY,
                    mode,
                    surface_particle_diagnostic_mode_name(mode),
                ),
            );
        }
    }
}

fn parse_surface_particle_diagnostic_mode(value: &str) -> Option<u32> {
    let normalized = value.trim().to_ascii_lowercase();
    match normalized.as_str() {
        "0" | "normal" | "off" => Some(0),
        "1" | "triangle-bands" | "triangles" | "topology" => Some(1),
        "2" | "projection-clamp" | "projection" | "clip" => Some(2),
        "3" | "no-dynamics" | "bright" => Some(3),
        "4" | "degenerate" | "triangle-reject" => Some(4),
        _ => normalized.parse::<u32>().ok().map(|mode| mode.min(4)),
    }
}

fn surface_particle_diagnostic_mode_name(mode: u32) -> &'static str {
    match mode {
        1 => "triangle-bands",
        2 => "projection-clamp",
        3 => "no-dynamics-bright",
        4 => "degenerate-triangle-accept",
        _ => "normal",
    }
}

fn android_system_property(name: &str) -> Option<String> {
    #[cfg(target_os = "android")]
    {
        const PROP_VALUE_MAX: usize = 92;
        let name = CString::new(name).ok()?;
        let mut value = [0 as c_char; PROP_VALUE_MAX];
        let len = unsafe { __system_property_get(name.as_ptr(), value.as_mut_ptr()) };
        if len <= 0 {
            return None;
        }
        let text = unsafe { CStr::from_ptr(value.as_ptr()) }
            .to_string_lossy()
            .trim()
            .to_string();
        if text.is_empty() {
            None
        } else {
            Some(text)
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = name;
        None
    }
}

fn store_surface_particle_panel_projection(projection: ReplayHandPanelProjection) {
    SURFACE_PARTICLE_PANEL_CENTER_X_BITS.store(projection.center[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_PANEL_CENTER_Y_BITS.store(projection.center[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_PANEL_CENTER_Z_BITS.store(projection.center[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_PANEL_RIGHT_X_BITS.store(projection.right[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_PANEL_RIGHT_Y_BITS.store(projection.right[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_PANEL_RIGHT_Z_BITS.store(projection.right[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_PANEL_UP_X_BITS.store(projection.up[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_PANEL_UP_Y_BITS.store(projection.up[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_PANEL_UP_Z_BITS.store(projection.up[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_PANEL_WIDTH_BITS.store(projection.width_meters.to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_PANEL_HEIGHT_BITS.store(projection.height_meters.to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_PANEL_TARGET_DISTANCE_BITS.store(
        projection.target_distance_meters.to_bits(),
        Ordering::Relaxed,
    );
    SURFACE_PARTICLE_PANEL_LEFT_EYE_OFFSET_RIGHT_BITS.store(
        projection.left_eye_offset_right_meters.to_bits(),
        Ordering::Relaxed,
    );
    SURFACE_PARTICLE_PANEL_RIGHT_EYE_OFFSET_RIGHT_BITS.store(
        projection.right_eye_offset_right_meters.to_bits(),
        Ordering::Relaxed,
    );
    SURFACE_PARTICLE_PANEL_POSE_VALID.store(projection.valid, Ordering::Relaxed);
}

fn reset_surface_particle_world_anchor() {
    SURFACE_PARTICLE_WORLD_ANCHOR_VALID.store(false, Ordering::Relaxed);
    SURFACE_PARTICLE_VIEWER_EYE_POSE_VALID.store(false, Ordering::Relaxed);
    SURFACE_PARTICLE_VIEWER_EYE_POSE_LOG_COUNT.store(0, Ordering::Relaxed);
    SURFACE_PARTICLE_WORLD_ANCHOR_SKIP_LOG_COUNT.store(0, Ordering::Relaxed);
    SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_VALID.store(false, Ordering::Relaxed);
    SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_MAPPED_VALID.store(false, Ordering::Relaxed);
    SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_LOG_COUNT.store(0, Ordering::Relaxed);
    SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_SKIP_LOG_COUNT.store(0, Ordering::Relaxed);
    SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LOG_COUNT.store(0, Ordering::Relaxed);
    SURFACE_PARTICLE_AUTO_RECENTER_LOG_COUNT.store(0, Ordering::Relaxed);
    SURFACE_PARTICLE_AUTO_RECENTER_LOCKED.store(false, Ordering::Relaxed);
    SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_TARGET_DISTANCE_BITS.store(0, Ordering::Relaxed);
    SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_CENTER_X_BITS.store(0, Ordering::Relaxed);
    SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_CENTER_Y_BITS.store(0, Ordering::Relaxed);
    SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_CENTER_Z_BITS.store(0, Ordering::Relaxed);
    SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_FORWARD_X_BITS.store(0, Ordering::Relaxed);
    SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_FORWARD_Y_BITS.store(0, Ordering::Relaxed);
    SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_FORWARD_Z_BITS.store(0, Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_REGISTRATION_VALID.store(false, Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_PROJECTION_VALID.store(false, Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_REGISTRATION_LOG_COUNT.store(0, Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_PROJECTION_LOG_COUNT.store(0, Ordering::Relaxed);
}

fn surface_particle_panel_forward(projection: ReplayHandPanelProjection) -> [f32; 3] {
    projection_panel_forward(projection.right, projection.up)
}

fn surface_particle_panel_eye_position(projection: ReplayHandPanelProjection) -> [f32; 3] {
    projection_panel_eye_position(
        projection.center,
        projection.right,
        projection.up,
        projection.target_distance_meters,
    )
}

fn surface_particle_world_anchor_pose_eligible(projection: ReplayHandPanelProjection) -> bool {
    let center_distance_squared = projection.center[0] * projection.center[0]
        + projection.center[1] * projection.center[1]
        + projection.center[2] * projection.center[2];
    projection.valid
        && projection.center.iter().all(|value| value.is_finite())
        && projection.right.iter().all(|value| value.is_finite())
        && projection.up.iter().all(|value| value.is_finite())
        && projection.width_meters.is_finite()
        && projection.height_meters.is_finite()
        && projection.target_distance_meters.is_finite()
        && projection.width_meters >= 0.1
        && projection.height_meters >= 0.1
        && projection.target_distance_meters >= 0.1
        && center_distance_squared >= 0.01
}

fn configured_surface_particle_world_anchor_center() -> [f32; 3] {
    [
        f32::from_bits(SURFACE_PARTICLE_WORLD_ANCHOR_X_BITS.load(Ordering::Relaxed)),
        f32::from_bits(SURFACE_PARTICLE_WORLD_ANCHOR_Y_BITS.load(Ordering::Relaxed)),
        f32::from_bits(SURFACE_PARTICLE_WORLD_ANCHOR_Z_BITS.load(Ordering::Relaxed)),
    ]
}

fn current_surface_particle_viewer_world_position() -> Option<[f32; 3]> {
    if !SURFACE_PARTICLE_VIEWER_EYE_POSE_VALID.load(Ordering::Relaxed) {
        return None;
    }
    let viewer = [
        f32::from_bits(SURFACE_PARTICLE_VIEWER_WORLD_X_BITS.load(Ordering::Relaxed)),
        f32::from_bits(SURFACE_PARTICLE_VIEWER_WORLD_Y_BITS.load(Ordering::Relaxed)),
        f32::from_bits(SURFACE_PARTICLE_VIEWER_WORLD_Z_BITS.load(Ordering::Relaxed)),
    ];
    if viewer.iter().all(|value| value.is_finite()) {
        Some(viewer)
    } else {
        None
    }
}

fn current_surface_particle_viewer_world_forward() -> Option<[f32; 3]> {
    if !SURFACE_PARTICLE_VIEWER_EYE_POSE_VALID.load(Ordering::Relaxed) {
        return None;
    }
    let forward = [
        f32::from_bits(SURFACE_PARTICLE_VIEWER_FORWARD_X_BITS.load(Ordering::Relaxed)),
        f32::from_bits(SURFACE_PARTICLE_VIEWER_FORWARD_Y_BITS.load(Ordering::Relaxed)),
        f32::from_bits(SURFACE_PARTICLE_VIEWER_FORWARD_Z_BITS.load(Ordering::Relaxed)),
    ];
    if forward.iter().all(|value| value.is_finite()) {
        Some(forward)
    } else {
        None
    }
}

fn store_surface_particle_viewer_eye_pose(
    viewer: [f32; 3],
    viewer_right: [f32; 3],
    viewer_up: [f32; 3],
    viewer_forward: [f32; 3],
    left_eye: [f32; 3],
    right_eye: [f32; 3],
) {
    SURFACE_PARTICLE_VIEWER_WORLD_X_BITS.store(viewer[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_VIEWER_WORLD_Y_BITS.store(viewer[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_VIEWER_WORLD_Z_BITS.store(viewer[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_VIEWER_RIGHT_X_BITS.store(viewer_right[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_VIEWER_RIGHT_Y_BITS.store(viewer_right[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_VIEWER_RIGHT_Z_BITS.store(viewer_right[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_VIEWER_UP_X_BITS.store(viewer_up[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_VIEWER_UP_Y_BITS.store(viewer_up[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_VIEWER_UP_Z_BITS.store(viewer_up[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_VIEWER_FORWARD_X_BITS.store(viewer_forward[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_VIEWER_FORWARD_Y_BITS.store(viewer_forward[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_VIEWER_FORWARD_Z_BITS.store(viewer_forward[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_LEFT_EYE_WORLD_X_BITS.store(left_eye[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_LEFT_EYE_WORLD_Y_BITS.store(left_eye[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_LEFT_EYE_WORLD_Z_BITS.store(left_eye[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_RIGHT_EYE_WORLD_X_BITS.store(right_eye[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_RIGHT_EYE_WORLD_Y_BITS.store(right_eye[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_RIGHT_EYE_WORLD_Z_BITS.store(right_eye[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_VIEWER_EYE_POSE_VALID.store(true, Ordering::Relaxed);
}

fn maybe_auto_recenter_surface_particle_world_anchor_on_viewer(viewer: [f32; 3]) -> bool {
    if SURFACE_PARTICLE_AUTO_RECENTER_LOCKED.load(Ordering::Relaxed) {
        return false;
    }
    let previous_center = configured_surface_particle_world_anchor_center();
    let Some(distance_meters) = viewer_sphere_center_distance_exceeds_threshold(
        viewer,
        previous_center,
        SURFACE_PARTICLE_AUTO_RECENTER_DISTANCE_METERS,
    ) else {
        return false;
    };

    let previous_anchor_valid = SURFACE_PARTICLE_WORLD_ANCHOR_VALID.load(Ordering::Relaxed);
    let (right, up, forward) = store_surface_particle_world_anchor_canonical(viewer);
    SURFACE_PARTICLE_AUTO_RECENTER_LOCKED.store(true, Ordering::Relaxed);
    if SURFACE_PARTICLE_AUTO_RECENTER_LOG_COUNT.fetch_add(1, Ordering::Relaxed)
        < SURFACE_PARTICLE_AUTO_RECENTER_LOG_LIMIT
    {
        android_log_info(
            "RQSpatialCameraPanelNative",
            &format!(
                "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=private-world-anchor-auto-recentered renderPolicy=native-vulkan-wsi-surface-panel privateSurfaceParticleWorldAnchorMode=spatial-sdk-fixed-sim-world-transform privateSurfaceParticleWorldAnchorRecenterSource=spatial-sdk-viewer-distance-guard privateSurfaceParticleWorldAnchorAutoRecenter=true privateSurfaceParticleWorldAnchorAutoRecenterPolicy=tracked-viewer-first-valid-distance-guard-once privateSurfaceParticleWorldAnchorAutoRecenterThresholdMeters={:.3} privateSurfaceParticleWorldAnchorAutoRecenterDistanceMeters={:.4} privateSurfaceParticleWorldAnchorPreviousValid={} privateSurfaceParticleWorldAnchorCenterSource=current-spatial-sdk-viewer-world-coordinate privateSurfaceParticleWorldAnchorBasis=spatial-world-canonical-axes privateSurfaceParticleWorldAnchorScaleSource=fixed-sim-meter-radius privateSurfaceParticleSimRegistration=sim-space-fixed-in-spatial-sdk-world-space privateSurfaceParticleSimTransform=spatial-world-from-sim-fixed-configured-origin-basis-meter-scale privateSurfaceParticleSimWorldAxesStable=true privateSurfaceParticleRecenterChangesCoordinateMapping=false privateSurfaceParticleRecenterChangesOnlySphereCenter=true previousWorldAnchorCenterM={:.4};{:.4};{:.4} worldAnchorCenterM={:.4};{:.4};{:.4} viewerWorldM={:.4};{:.4};{:.4} worldAnchorRight={:.4};{:.4};{:.4} worldAnchorUp={:.4};{:.4};{:.4} worldAnchorForward={:.4};{:.4};{:.4}",
                SURFACE_PARTICLE_AUTO_RECENTER_DISTANCE_METERS,
                distance_meters,
                bool_token(previous_anchor_valid),
                previous_center[0],
                previous_center[1],
                previous_center[2],
                viewer[0],
                viewer[1],
                viewer[2],
                viewer[0],
                viewer[1],
                viewer[2],
                right[0],
                right[1],
                right[2],
                up[0],
                up[1],
                up[2],
                forward[0],
                forward[1],
                forward[2],
            ),
        );
    }
    true
}

fn store_surface_particle_world_anchor_canonical(
    center: [f32; 3],
) -> ([f32; 3], [f32; 3], [f32; 3]) {
    let (right, up, forward) = canonical_world_basis();
    SURFACE_PARTICLE_WORLD_ANCHOR_VALID.store(false, Ordering::Relaxed);
    SURFACE_PARTICLE_WORLD_ANCHOR_X_BITS.store(center[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_WORLD_ANCHOR_Y_BITS.store(center[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_WORLD_ANCHOR_Z_BITS.store(center[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_WORLD_RIGHT_X_BITS.store(right[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_WORLD_RIGHT_Y_BITS.store(right[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_WORLD_RIGHT_Z_BITS.store(right[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_WORLD_UP_X_BITS.store(up[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_WORLD_UP_Y_BITS.store(up[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_WORLD_UP_Z_BITS.store(up[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_WORLD_FORWARD_X_BITS.store(forward[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_WORLD_FORWARD_Y_BITS.store(forward[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_WORLD_FORWARD_Z_BITS.store(forward[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LOG_COUNT.store(0, Ordering::Relaxed);
    SURFACE_PARTICLE_WORLD_ANCHOR_VALID.store(true, Ordering::Relaxed);
    (right, up, forward)
}

fn capture_surface_particle_world_anchor_if_needed(_projection: ReplayHandPanelProjection) {
    if SURFACE_PARTICLE_WORLD_ANCHOR_VALID.load(Ordering::Relaxed) {
        return;
    }
    let center = configured_surface_particle_world_anchor_center();
    let (right, up, forward) = store_surface_particle_world_anchor_canonical(center);
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=private-world-anchor-captured renderPolicy=native-vulkan-wsi-surface-panel privateSurfaceParticleWorldAnchorMode=spatial-sdk-fixed-sim-world-transform privateSurfaceParticleWorldAnchorFallbackMode=configured-fixed-sim-world-transform privateSurfaceParticleWorldAnchorEligibility=configured-spatial-world-transform privateSurfaceParticleWorldAnchorStable=true privateSurfaceParticleWorldAnchorComputeSource=spatial-sdk-world-coordinates privateSurfaceParticleWorldAnchorCenterSource=configured-spatial-world-coordinate privateSurfaceParticleWorldAnchorBasis=spatial-world-canonical-axes privateSurfaceParticleWorldAnchorScaleSource=fixed-sim-meter-radius privateSurfaceParticleSimRegistration=sim-space-fixed-in-spatial-sdk-world-space privateSurfaceParticleSimTransform=spatial-world-from-sim-fixed-configured-origin-basis-meter-scale privateSurfaceParticlePublicFallbackActive=false worldAnchorCenterM={:.4};{:.4};{:.4} worldAnchorRight={:.4};{:.4};{:.4} worldAnchorUp={:.4};{:.4};{:.4} worldAnchorForward={:.4};{:.4};{:.4}",
            center[0],
            center[1],
            center[2],
            right[0],
            right[1],
            right[2],
            up[0],
            up[1],
            up[2],
            forward[0],
            forward[1],
            forward[2],
        ),
    );
}

fn update_surface_particle_openxr_world_anchor(
    mapper: &mut SurfaceParticleOpenXrViewMapper,
    panel_projection: ReplayHandPanelProjection,
) {
    if !surface_particle_world_anchor_pose_eligible(panel_projection) {
        if SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_SKIP_LOG_COUNT.fetch_add(1, Ordering::Relaxed) < 3 {
            android_log_info(
                "RQSpatialCameraPanelNative",
                &format!(
                    "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=private-openxr-world-anchor-capture-skipped renderPolicy=native-vulkan-wsi-surface-panel privateSurfaceParticleWorldAnchorMode=openxr-local-floor-fixed-anchor privateSurfaceParticleWorldAnchorEligibility=requires-floor-space-panel-pose privateSurfaceParticleOpenXrWorldAnchorStable=false privateSurfaceParticlePublicFallbackActive=false panelCenterM={:.4};{:.4};{:.4} panelWidthMeters={:.3} panelHeightMeters={:.3} panelTargetDistanceMeters={:.3} {}",
                    panel_projection.center[0],
                    panel_projection.center[1],
                    panel_projection.center[2],
                    panel_projection.width_meters,
                    panel_projection.height_meters,
                    panel_projection.target_distance_meters,
                    mapper.marker_fields(),
                ),
            );
        }
        return;
    }
    let Some(mapping) = mapper.current_mapping(panel_projection) else {
        if SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_SKIP_LOG_COUNT.fetch_add(1, Ordering::Relaxed) < 3 {
            android_log_info(
                "RQSpatialCameraPanelNative",
                &format!(
                    "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=private-openxr-world-anchor-capture-skipped renderPolicy=native-vulkan-wsi-surface-panel privateSurfaceParticleWorldAnchorMode=openxr-local-floor-fixed-anchor privateSurfaceParticleWorldAnchorEligibility=openxr-view-mapping-unavailable privateSurfaceParticleOpenXrWorldAnchorStable=false privateSurfaceParticlePublicFallbackActive=false panelCenterM={:.4};{:.4};{:.4} {}",
                    panel_projection.center[0],
                    panel_projection.center[1],
                    panel_projection.center[2],
                    mapper.marker_fields(),
                ),
            );
        }
        return;
    };
    if !SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_VALID.load(Ordering::Relaxed) {
        let raw_anchor = add3(
            mapping.view_position,
            scale3(mapping.raw_forward, panel_projection.target_distance_meters),
        );
        let raw_forward = normalize_or(mapping.raw_forward, [0.0, 0.0, -1.0]);
        SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_RAW_X_BITS
            .store(raw_anchor[0].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_RAW_Y_BITS
            .store(raw_anchor[1].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_RAW_Z_BITS
            .store(raw_anchor[2].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_OPENXR_WORLD_FORWARD_RAW_X_BITS
            .store(raw_forward[0].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_OPENXR_WORLD_FORWARD_RAW_Y_BITS
            .store(raw_forward[1].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_OPENXR_WORLD_FORWARD_RAW_Z_BITS
            .store(raw_forward[2].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_VALID.store(true, Ordering::Relaxed);
        android_log_info(
            "RQSpatialCameraPanelNative",
            &format!(
                "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=private-openxr-world-anchor-captured renderPolicy=native-vulkan-wsi-surface-panel privateSurfaceParticleWorldAnchorMode=openxr-local-floor-fixed-anchor privateSurfaceParticleWorldAnchorEligibility=floor-space-panel-pose+openxr-view privateSurfaceParticleOpenXrWorldAnchorStable=true privateSurfaceParticlePublicFallbackActive=false rawOpenXrWorldAnchorCenterM={:.4};{:.4};{:.4} rawOpenXrWorldAnchorForward={:.4};{:.4};{:.4} {}",
                raw_anchor[0],
                raw_anchor[1],
                raw_anchor[2],
                raw_forward[0],
                raw_forward[1],
                raw_forward[2],
                mapper.marker_fields(),
            ),
        );
    }
    let raw_anchor = [
        f32::from_bits(SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_RAW_X_BITS.load(Ordering::Relaxed)),
        f32::from_bits(SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_RAW_Y_BITS.load(Ordering::Relaxed)),
        f32::from_bits(SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_RAW_Z_BITS.load(Ordering::Relaxed)),
    ];
    let raw_forward = normalize_or(
        [
            f32::from_bits(
                SURFACE_PARTICLE_OPENXR_WORLD_FORWARD_RAW_X_BITS.load(Ordering::Relaxed),
            ),
            f32::from_bits(
                SURFACE_PARTICLE_OPENXR_WORLD_FORWARD_RAW_Y_BITS.load(Ordering::Relaxed),
            ),
            f32::from_bits(
                SURFACE_PARTICLE_OPENXR_WORLD_FORWARD_RAW_Z_BITS.load(Ordering::Relaxed),
            ),
        ],
        mapping.raw_forward,
    );
    let mapped_anchor = mapping.map_point(raw_anchor);
    let mapped_forward = mapping.map_vector(raw_forward);
    SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_MAPPED_X_BITS
        .store(mapped_anchor[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_MAPPED_Y_BITS
        .store(mapped_anchor[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_MAPPED_Z_BITS
        .store(mapped_anchor[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OPENXR_WORLD_FORWARD_MAPPED_X_BITS
        .store(mapped_forward[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OPENXR_WORLD_FORWARD_MAPPED_Y_BITS
        .store(mapped_forward[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OPENXR_WORLD_FORWARD_MAPPED_Z_BITS
        .store(mapped_forward[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_MAPPED_VALID.store(true, Ordering::Relaxed);
    if SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_LOG_COUNT.fetch_add(1, Ordering::Relaxed)
        < SURFACE_PARTICLE_OPENXR_WORLD_ANCHOR_MAPPED_LOG_LIMIT
    {
        android_log_info(
            "RQSpatialCameraPanelNative",
            &format!(
                "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=private-openxr-world-anchor-mapped renderPolicy=native-vulkan-wsi-surface-panel privateSurfaceParticleWorldAnchorMode=openxr-local-floor-fixed-anchor privateSurfaceParticleWorldAnchorMapped=true privateSurfaceParticleOpenXrWorldAnchorStable=true privateSurfaceParticlePublicFallbackActive=false mappedWorldAnchorCenterM={:.4};{:.4};{:.4} mappedWorldAnchorForward={:.4};{:.4};{:.4} panelCenterM={:.4};{:.4};{:.4} {}",
                mapped_anchor[0],
                mapped_anchor[1],
                mapped_anchor[2],
                mapped_forward[0],
                mapped_forward[1],
                mapped_forward[2],
                panel_projection.center[0],
                panel_projection.center[1],
                panel_projection.center[2],
                mapper.marker_fields(),
            ),
        );
    }
    update_surface_particle_off_axis_projection(mapping, panel_projection);
}

fn store_surface_particle_off_axis_registration(
    registration: SurfaceParticleFixedWorldRegistration,
) {
    SURFACE_PARTICLE_OFF_AXIS_RAW_ORIGIN_X_BITS
        .store(registration.raw_origin[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_RAW_ORIGIN_Y_BITS
        .store(registration.raw_origin[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_RAW_ORIGIN_Z_BITS
        .store(registration.raw_origin[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_RAW_RIGHT_X_BITS
        .store(registration.raw_right[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_RAW_RIGHT_Y_BITS
        .store(registration.raw_right[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_RAW_RIGHT_Z_BITS
        .store(registration.raw_right[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_RAW_UP_X_BITS
        .store(registration.raw_up[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_RAW_UP_Y_BITS
        .store(registration.raw_up[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_RAW_UP_Z_BITS
        .store(registration.raw_up[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_RAW_FORWARD_X_BITS
        .store(registration.raw_forward[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_RAW_FORWARD_Y_BITS
        .store(registration.raw_forward[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_RAW_FORWARD_Z_BITS
        .store(registration.raw_forward[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_SCENE_ORIGIN_X_BITS
        .store(registration.scene_origin[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_SCENE_ORIGIN_Y_BITS
        .store(registration.scene_origin[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_SCENE_ORIGIN_Z_BITS
        .store(registration.scene_origin[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_SCENE_RIGHT_X_BITS
        .store(registration.scene_right[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_SCENE_RIGHT_Y_BITS
        .store(registration.scene_right[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_SCENE_RIGHT_Z_BITS
        .store(registration.scene_right[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_SCENE_UP_X_BITS
        .store(registration.scene_up[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_SCENE_UP_Y_BITS
        .store(registration.scene_up[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_SCENE_UP_Z_BITS
        .store(registration.scene_up[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_SCENE_FORWARD_X_BITS
        .store(registration.scene_forward[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_SCENE_FORWARD_Y_BITS
        .store(registration.scene_forward[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_SCENE_FORWARD_Z_BITS
        .store(registration.scene_forward[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_REGISTRATION_VALID.store(true, Ordering::Relaxed);
}

fn current_surface_particle_off_axis_registration() -> Option<SurfaceParticleFixedWorldRegistration>
{
    if !SURFACE_PARTICLE_OFF_AXIS_REGISTRATION_VALID.load(Ordering::Relaxed) {
        return None;
    }
    Some(SurfaceParticleFixedWorldRegistration {
        raw_origin: [
            f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_RAW_ORIGIN_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_RAW_ORIGIN_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_RAW_ORIGIN_Z_BITS.load(Ordering::Relaxed)),
        ],
        raw_right: normalize_or(
            [
                f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_RAW_RIGHT_X_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_RAW_RIGHT_Y_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_RAW_RIGHT_Z_BITS.load(Ordering::Relaxed)),
            ],
            [1.0, 0.0, 0.0],
        ),
        raw_up: normalize_or(
            [
                f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_RAW_UP_X_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_RAW_UP_Y_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_RAW_UP_Z_BITS.load(Ordering::Relaxed)),
            ],
            [0.0, 1.0, 0.0],
        ),
        raw_forward: normalize_or(
            [
                f32::from_bits(
                    SURFACE_PARTICLE_OFF_AXIS_RAW_FORWARD_X_BITS.load(Ordering::Relaxed),
                ),
                f32::from_bits(
                    SURFACE_PARTICLE_OFF_AXIS_RAW_FORWARD_Y_BITS.load(Ordering::Relaxed),
                ),
                f32::from_bits(
                    SURFACE_PARTICLE_OFF_AXIS_RAW_FORWARD_Z_BITS.load(Ordering::Relaxed),
                ),
            ],
            [0.0, 0.0, -1.0],
        ),
        scene_origin: [
            f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_SCENE_ORIGIN_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_SCENE_ORIGIN_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_SCENE_ORIGIN_Z_BITS.load(Ordering::Relaxed)),
        ],
        scene_right: normalize_or(
            [
                f32::from_bits(
                    SURFACE_PARTICLE_OFF_AXIS_SCENE_RIGHT_X_BITS.load(Ordering::Relaxed),
                ),
                f32::from_bits(
                    SURFACE_PARTICLE_OFF_AXIS_SCENE_RIGHT_Y_BITS.load(Ordering::Relaxed),
                ),
                f32::from_bits(
                    SURFACE_PARTICLE_OFF_AXIS_SCENE_RIGHT_Z_BITS.load(Ordering::Relaxed),
                ),
            ],
            [1.0, 0.0, 0.0],
        ),
        scene_up: normalize_or(
            [
                f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_SCENE_UP_X_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_SCENE_UP_Y_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_SCENE_UP_Z_BITS.load(Ordering::Relaxed)),
            ],
            [0.0, 1.0, 0.0],
        ),
        scene_forward: normalize_or(
            [
                f32::from_bits(
                    SURFACE_PARTICLE_OFF_AXIS_SCENE_FORWARD_X_BITS.load(Ordering::Relaxed),
                ),
                f32::from_bits(
                    SURFACE_PARTICLE_OFF_AXIS_SCENE_FORWARD_Y_BITS.load(Ordering::Relaxed),
                ),
                f32::from_bits(
                    SURFACE_PARTICLE_OFF_AXIS_SCENE_FORWARD_Z_BITS.load(Ordering::Relaxed),
                ),
            ],
            [0.0, 0.0, -1.0],
        ),
    })
}

fn capture_surface_particle_off_axis_registration_if_needed(
    mapping: SurfaceParticleOpenXrPanelMapping,
    panel_projection: ReplayHandPanelProjection,
) {
    if SURFACE_PARTICLE_OFF_AXIS_REGISTRATION_VALID.load(Ordering::Relaxed)
        || !surface_particle_world_anchor_pose_eligible(panel_projection)
    {
        return;
    }
    let scene_forward = surface_particle_panel_forward(panel_projection);
    let scene_origin = surface_particle_panel_eye_position(panel_projection);
    let registration = SurfaceParticleFixedWorldRegistration {
        raw_origin: mapping.view_position,
        raw_right: normalize_or(mapping.raw_right, [1.0, 0.0, 0.0]),
        raw_up: normalize_or(mapping.raw_up, [0.0, 1.0, 0.0]),
        raw_forward: normalize_or(mapping.raw_forward, [0.0, 0.0, -1.0]),
        scene_origin,
        scene_right: normalize_or(panel_projection.right, [1.0, 0.0, 0.0]),
        scene_up: normalize_or(panel_projection.up, [0.0, 1.0, 0.0]),
        scene_forward,
    };
    store_surface_particle_off_axis_registration(registration);
    if SURFACE_PARTICLE_OFF_AXIS_REGISTRATION_LOG_COUNT.fetch_add(1, Ordering::Relaxed) == 0 {
        android_log_info(
            "RQSpatialCameraPanelNative",
            &format!(
                "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=private-openxr-diagnostic-registration-captured renderPolicy=native-vulkan-wsi-surface-panel privateSurfaceParticleOpenXrDiagnosticProjection=registered-openxr-eye-through-start-basis privateSurfaceParticleOpenXrViewDrawAuthority=false privateSurfaceParticleMainDrawProjection=spatial-sdk-world-explicit-viewer-camera-to-panel-plane privateSurfaceParticleMainDrawCameraBasisSource=Scene.getViewerPose-position+forward-x-mirror-corrected-roll-stable privateSurfaceParticleCarrierPanelForwardSource=spatial-sdk-presentation-plane-only privateSurfaceParticleEyePoseSource=Scene.getViewerPose+Scene.getEyeOffsets privateSurfaceParticlePanelPoseSource=Scene.getViewerPose-derived-panel-plane privateSurfaceParticlePanelDefinesEye=false privateSurfaceParticleWorldAnchorCenterSource=configured-spatial-world-coordinate privateSurfaceParticleWorldAnchorBasis=spatial-world-canonical-axes privateSurfaceParticleWorldAnchorScaleSource=fixed-sim-meter-radius privateSurfaceParticleSimRegistration=sim-space-fixed-in-spatial-sdk-world-space privateSurfaceParticleSimTransform=spatial-world-from-sim-fixed-configured-origin-basis-meter-scale privateSurfaceParticleCameraRealignEachFrame=false privateSurfaceParticleOffAxisStereoProjection=true rawStartEyeM={:.4};{:.4};{:.4} sceneStartEyeM={:.4};{:.4};{:.4} sceneStartForward={:.4};{:.4};{:.4} panelTargetDistanceMeters={:.3}",
                registration.raw_origin[0],
                registration.raw_origin[1],
                registration.raw_origin[2],
                registration.scene_origin[0],
                registration.scene_origin[1],
                registration.scene_origin[2],
                registration.scene_forward[0],
                registration.scene_forward[1],
                registration.scene_forward[2],
                panel_projection.target_distance_meters,
            ),
        );
    }
}

fn store_surface_particle_off_axis_panel_projection(projection: ReplayHandPanelProjection) {
    SURFACE_PARTICLE_OFF_AXIS_PANEL_CENTER_X_BITS
        .store(projection.center[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_PANEL_CENTER_Y_BITS
        .store(projection.center[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_PANEL_CENTER_Z_BITS
        .store(projection.center[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_PANEL_RIGHT_X_BITS
        .store(projection.right[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_PANEL_RIGHT_Y_BITS
        .store(projection.right[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_PANEL_RIGHT_Z_BITS
        .store(projection.right[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_PANEL_UP_X_BITS.store(projection.up[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_PANEL_UP_Y_BITS.store(projection.up[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_PANEL_UP_Z_BITS.store(projection.up[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_OFF_AXIS_PROJECTION_VALID.store(projection.valid, Ordering::Relaxed);
}

fn update_surface_particle_off_axis_projection(
    mapping: SurfaceParticleOpenXrPanelMapping,
    panel_projection: ReplayHandPanelProjection,
) {
    capture_surface_particle_off_axis_registration_if_needed(mapping, panel_projection);
    let Some(registration) = current_surface_particle_off_axis_registration() else {
        return;
    };
    let eye_position = registration.map_point(mapping.view_position);
    let right = registration.map_vector(mapping.raw_right);
    let up = registration.map_vector(mapping.raw_up);
    let forward = normalize_or(
        registration.map_vector(mapping.raw_forward),
        surface_particle_panel_forward(panel_projection),
    );
    let center = add3(
        eye_position,
        scale3(forward, panel_projection.target_distance_meters),
    );
    let projection = ReplayHandPanelProjection {
        center,
        right,
        up,
        valid: true,
        ..panel_projection
    };
    store_surface_particle_off_axis_panel_projection(projection);
    if SURFACE_PARTICLE_OFF_AXIS_PROJECTION_LOG_COUNT.fetch_add(1, Ordering::Relaxed)
        < SURFACE_PARTICLE_OFF_AXIS_PROJECTION_LOG_LIMIT
    {
        android_log_info(
            "RQSpatialCameraPanelNative",
            &format!(
                "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=private-openxr-diagnostic-projection-updated renderPolicy=native-vulkan-wsi-surface-panel privateSurfaceParticleOpenXrDiagnosticProjection=registered-openxr-eye-through-start-basis privateSurfaceParticleOpenXrViewDrawAuthority=false privateSurfaceParticleMainDrawProjection=spatial-sdk-world-explicit-viewer-camera-to-panel-plane privateSurfaceParticleMainDrawCameraBasisSource=Scene.getViewerPose-position+forward-x-mirror-corrected-roll-stable privateSurfaceParticleCarrierPanelForwardSource=spatial-sdk-presentation-plane-only privateSurfaceParticleEyePoseSource=Scene.getViewerPose+Scene.getEyeOffsets privateSurfaceParticlePanelPoseSource=Scene.getViewerPose-derived-panel-plane privateSurfaceParticlePanelDefinesEye=false privateSurfaceParticleWorldAnchorCenterSource=configured-spatial-world-coordinate privateSurfaceParticleWorldAnchorBasis=spatial-world-canonical-axes privateSurfaceParticleWorldAnchorScaleSource=fixed-sim-meter-radius privateSurfaceParticleSimRegistration=sim-space-fixed-in-spatial-sdk-world-space privateSurfaceParticleSimTransform=spatial-world-from-sim-fixed-configured-origin-basis-meter-scale privateSurfaceParticleCameraRealignEachFrame=false privateSurfaceParticleOffAxisStereoProjection=true registeredEyeM={:.4};{:.4};{:.4} registeredPanelCenterM={:.4};{:.4};{:.4} registeredPanelForward={:.4};{:.4};{:.4} panelTargetDistanceMeters={:.3}",
                eye_position[0],
                eye_position[1],
                eye_position[2],
                center[0],
                center[1],
                center[2],
                forward[0],
                forward[1],
                forward[2],
                panel_projection.target_distance_meters,
            ),
        );
    }
}

fn current_surface_particle_spatial_world_panel_projection(
    panel_projection: ReplayHandPanelProjection,
) -> ReplayHandPanelProjection {
    panel_projection
}

fn current_surface_particle_spatial_world_draw_camera_projection(
    panel_projection: ReplayHandPanelProjection,
) -> ReplayHandPanelProjection {
    let Some(viewer) = current_surface_particle_viewer_world_position() else {
        return current_surface_particle_spatial_world_panel_projection(panel_projection);
    };
    let viewer_forward =
        current_surface_particle_viewer_world_forward().unwrap_or([0.0, 0.0, -1.0]);
    let (right, up, _) = viewer_forward_roll_stable_camera_basis(viewer_forward);
    ReplayHandPanelProjection {
        center: viewer_forward_roll_stable_panel_center(
            viewer,
            viewer_forward,
            panel_projection.target_distance_meters,
        ),
        right,
        up,
        valid: true,
        ..panel_projection
    }
}

#[allow(dead_code)]
fn current_surface_particle_off_axis_panel_projection(
    fallback: ReplayHandPanelProjection,
) -> ReplayHandPanelProjection {
    if !SURFACE_PARTICLE_OFF_AXIS_PROJECTION_VALID.load(Ordering::Relaxed) {
        return fallback;
    }
    ReplayHandPanelProjection {
        center: [
            f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_PANEL_CENTER_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_PANEL_CENTER_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_PANEL_CENTER_Z_BITS.load(Ordering::Relaxed)),
        ],
        right: normalize_or(
            [
                f32::from_bits(
                    SURFACE_PARTICLE_OFF_AXIS_PANEL_RIGHT_X_BITS.load(Ordering::Relaxed),
                ),
                f32::from_bits(
                    SURFACE_PARTICLE_OFF_AXIS_PANEL_RIGHT_Y_BITS.load(Ordering::Relaxed),
                ),
                f32::from_bits(
                    SURFACE_PARTICLE_OFF_AXIS_PANEL_RIGHT_Z_BITS.load(Ordering::Relaxed),
                ),
            ],
            fallback.right,
        ),
        up: normalize_or(
            [
                f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_PANEL_UP_X_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_PANEL_UP_Y_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_OFF_AXIS_PANEL_UP_Z_BITS.load(Ordering::Relaxed)),
            ],
            fallback.up,
        ),
        valid: true,
        ..fallback
    }
}

fn current_surface_particle_world_anchor(
    panel_projection: ReplayHandPanelProjection,
) -> SurfaceParticleWorldAnchor {
    if SURFACE_PARTICLE_WORLD_ANCHOR_VALID.load(Ordering::Relaxed) {
        let world_anchor = [
            f32::from_bits(SURFACE_PARTICLE_WORLD_ANCHOR_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(SURFACE_PARTICLE_WORLD_ANCHOR_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(SURFACE_PARTICLE_WORLD_ANCHOR_Z_BITS.load(Ordering::Relaxed)),
        ];
        let world_right = normalize_or(
            [
                f32::from_bits(SURFACE_PARTICLE_WORLD_RIGHT_X_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_WORLD_RIGHT_Y_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_WORLD_RIGHT_Z_BITS.load(Ordering::Relaxed)),
            ],
            [1.0, 0.0, 0.0],
        );
        let world_up = normalize_or(
            [
                f32::from_bits(SURFACE_PARTICLE_WORLD_UP_X_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_WORLD_UP_Y_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_WORLD_UP_Z_BITS.load(Ordering::Relaxed)),
            ],
            [0.0, 1.0, 0.0],
        );
        let world_forward = normalize_or(
            [
                f32::from_bits(SURFACE_PARTICLE_WORLD_FORWARD_X_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_WORLD_FORWARD_Y_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_WORLD_FORWARD_Z_BITS.load(Ordering::Relaxed)),
            ],
            [0.0, 0.0, -1.0],
        );
        log_surface_particle_scene_fixed_world_anchor_sample(
            world_anchor,
            world_forward,
            panel_projection,
        );
        return SurfaceParticleWorldAnchor {
            center: world_anchor,
            right: world_right,
            up: world_up,
            forward: world_forward,
            valid: true,
        };
    }
    let (right, up, forward) = canonical_world_basis();
    SurfaceParticleWorldAnchor {
        center: configured_surface_particle_world_anchor_center(),
        right,
        up,
        forward,
        valid: false,
    }
}

fn log_surface_particle_scene_fixed_world_anchor_sample_if_captured(
    panel_projection: ReplayHandPanelProjection,
) {
    if !SURFACE_PARTICLE_WORLD_ANCHOR_VALID.load(Ordering::Relaxed) {
        return;
    }
    let world_anchor = [
        f32::from_bits(SURFACE_PARTICLE_WORLD_ANCHOR_X_BITS.load(Ordering::Relaxed)),
        f32::from_bits(SURFACE_PARTICLE_WORLD_ANCHOR_Y_BITS.load(Ordering::Relaxed)),
        f32::from_bits(SURFACE_PARTICLE_WORLD_ANCHOR_Z_BITS.load(Ordering::Relaxed)),
    ];
    let world_forward = normalize_or(
        [
            f32::from_bits(SURFACE_PARTICLE_WORLD_FORWARD_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(SURFACE_PARTICLE_WORLD_FORWARD_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(SURFACE_PARTICLE_WORLD_FORWARD_Z_BITS.load(Ordering::Relaxed)),
        ],
        [0.0, 0.0, -1.0],
    );
    log_surface_particle_scene_fixed_world_anchor_sample(
        world_anchor,
        world_forward,
        panel_projection,
    );
}

fn log_surface_particle_scene_fixed_world_anchor_sample(
    world_anchor: [f32; 3],
    world_forward: [f32; 3],
    panel_projection: ReplayHandPanelProjection,
) {
    let panel_forward = surface_particle_panel_forward(panel_projection);
    let target_distance_bits = panel_projection.target_distance_meters.to_bits();
    let previous_target_distance_bits =
        SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_TARGET_DISTANCE_BITS.load(Ordering::Relaxed);
    let previous_panel_center = [
        f32::from_bits(
            SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_CENTER_X_BITS
                .load(Ordering::Relaxed),
        ),
        f32::from_bits(
            SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_CENTER_Y_BITS
                .load(Ordering::Relaxed),
        ),
        f32::from_bits(
            SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_CENTER_Z_BITS
                .load(Ordering::Relaxed),
        ),
    ];
    let previous_panel_forward = [
        f32::from_bits(
            SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_FORWARD_X_BITS
                .load(Ordering::Relaxed),
        ),
        f32::from_bits(
            SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_FORWARD_Y_BITS
                .load(Ordering::Relaxed),
        ),
        f32::from_bits(
            SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_FORWARD_Z_BITS
                .load(Ordering::Relaxed),
        ),
    ];
    let have_previous = previous_target_distance_bits != 0;
    let previous_target_distance = f32::from_bits(previous_target_distance_bits);
    let target_distance_changed = !have_previous
        || (previous_target_distance - panel_projection.target_distance_meters).abs() >= 0.001;
    let panel_center_changed = have_previous
        && squared_distance3(previous_panel_center, panel_projection.center) >= 0.0001;
    let panel_forward_changed =
        have_previous && squared_distance3(previous_panel_forward, panel_forward) >= 0.0004;
    if !target_distance_changed && !panel_center_changed && !panel_forward_changed {
        return;
    }
    if SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LOG_COUNT.fetch_add(1, Ordering::Relaxed)
        >= SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LOG_LIMIT
    {
        return;
    }
    SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_TARGET_DISTANCE_BITS
        .store(target_distance_bits, Ordering::Relaxed);
    SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_CENTER_X_BITS
        .store(panel_projection.center[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_CENTER_Y_BITS
        .store(panel_projection.center[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_CENTER_Z_BITS
        .store(panel_projection.center[2].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_FORWARD_X_BITS
        .store(panel_forward[0].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_FORWARD_Y_BITS
        .store(panel_forward[1].to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_SCENE_FIXED_WORLD_ANCHOR_LAST_PANEL_FORWARD_Z_BITS
        .store(panel_forward[2].to_bits(), Ordering::Relaxed);
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=private-scene-fixed-world-anchor-sampled renderPolicy=native-vulkan-wsi-surface-panel privateSurfaceParticleWorldAnchorMode=spatial-sdk-fixed-sim-world-transform privateSurfaceParticleWorldAnchorComputeSource=spatial-sdk-world-coordinates privateSurfaceParticleWorldAnchorCenterSource=configured-spatial-world-coordinate privateSurfaceParticleWorldAnchorBasis=spatial-world-canonical-axes privateSurfaceParticleWorldAnchorScaleSource=fixed-sim-meter-radius privateSurfaceParticleWorldAnchorStable=true privateSurfaceParticlePublicFallbackActive=false worldAnchorCenterM={:.4};{:.4};{:.4} worldAnchorForward={:.4};{:.4};{:.4} panelCenterM={:.4};{:.4};{:.4} panelForward={:.4};{:.4};{:.4} panelTargetDistanceMeters={:.3}",
            world_anchor[0],
            world_anchor[1],
            world_anchor[2],
            world_forward[0],
            world_forward[1],
            world_forward[2],
            panel_projection.center[0],
            panel_projection.center[1],
            panel_projection.center[2],
            panel_forward[0],
            panel_forward[1],
            panel_forward[2],
            panel_projection.target_distance_meters,
        ),
    );
}

fn squared_distance3(left: [f32; 3], right: [f32; 3]) -> f32 {
    let x = left[0] - right[0];
    let y = left[1] - right[1];
    let z = left[2] - right[2];
    x * x + y * y + z * z
}

fn current_surface_particle_panel_projection() -> ReplayHandPanelProjection {
    ReplayHandPanelProjection {
        center: [
            f32::from_bits(SURFACE_PARTICLE_PANEL_CENTER_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(SURFACE_PARTICLE_PANEL_CENTER_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(SURFACE_PARTICLE_PANEL_CENTER_Z_BITS.load(Ordering::Relaxed)),
        ],
        right: [
            f32::from_bits(SURFACE_PARTICLE_PANEL_RIGHT_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(SURFACE_PARTICLE_PANEL_RIGHT_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(SURFACE_PARTICLE_PANEL_RIGHT_Z_BITS.load(Ordering::Relaxed)),
        ],
        up: [
            f32::from_bits(SURFACE_PARTICLE_PANEL_UP_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(SURFACE_PARTICLE_PANEL_UP_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(SURFACE_PARTICLE_PANEL_UP_Z_BITS.load(Ordering::Relaxed)),
        ],
        width_meters: f32::from_bits(SURFACE_PARTICLE_PANEL_WIDTH_BITS.load(Ordering::Relaxed)),
        height_meters: f32::from_bits(SURFACE_PARTICLE_PANEL_HEIGHT_BITS.load(Ordering::Relaxed)),
        target_distance_meters: f32::from_bits(
            SURFACE_PARTICLE_PANEL_TARGET_DISTANCE_BITS.load(Ordering::Relaxed),
        ),
        left_eye_offset_right_meters: f32::from_bits(
            SURFACE_PARTICLE_PANEL_LEFT_EYE_OFFSET_RIGHT_BITS.load(Ordering::Relaxed),
        ),
        right_eye_offset_right_meters: f32::from_bits(
            SURFACE_PARTICLE_PANEL_RIGHT_EYE_OFFSET_RIGHT_BITS.load(Ordering::Relaxed),
        ),
        valid: SURFACE_PARTICLE_PANEL_POSE_VALID.load(Ordering::Relaxed),
    }
}

fn clamp_f32(value: f32, min: f32, max: f32) -> f32 {
    if value.is_finite() {
        value.clamp(min, max)
    } else {
        min
    }
}

fn finite_or(value: f32, fallback: f32) -> f32 {
    if value.is_finite() {
        value
    } else {
        fallback
    }
}

fn surface_particle_panel_eye_offset_right_meters(
    panel_projection: ReplayHandPanelProjection,
    eye_index: u32,
) -> f32 {
    let (value, fallback) = if eye_index == 0 {
        (
            panel_projection.left_eye_offset_right_meters,
            DEFAULT_SURFACE_PARTICLE_LEFT_EYE_OFFSET_RIGHT_METERS,
        )
    } else {
        (
            panel_projection.right_eye_offset_right_meters,
            DEFAULT_SURFACE_PARTICLE_RIGHT_EYE_OFFSET_RIGHT_METERS,
        )
    };
    finite_or(value, fallback).clamp(-0.12, 0.12)
}

fn current_surface_particle_eye_world(
    panel_projection: ReplayHandPanelProjection,
    eye_index: u32,
) -> [f32; 3] {
    if let Some(viewer) = current_surface_particle_viewer_world_position() {
        return viewer_forward_roll_stable_eye_position(
            viewer,
            current_surface_particle_viewer_world_forward().unwrap_or([0.0, 0.0, -1.0]),
            surface_particle_panel_eye_offset_right_meters(panel_projection, eye_index),
        );
    }
    if SURFACE_PARTICLE_VIEWER_EYE_POSE_VALID.load(Ordering::Relaxed) {
        let eye = if eye_index == 0 {
            [
                f32::from_bits(SURFACE_PARTICLE_LEFT_EYE_WORLD_X_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_LEFT_EYE_WORLD_Y_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_LEFT_EYE_WORLD_Z_BITS.load(Ordering::Relaxed)),
            ]
        } else {
            [
                f32::from_bits(SURFACE_PARTICLE_RIGHT_EYE_WORLD_X_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_RIGHT_EYE_WORLD_Y_BITS.load(Ordering::Relaxed)),
                f32::from_bits(SURFACE_PARTICLE_RIGHT_EYE_WORLD_Z_BITS.load(Ordering::Relaxed)),
            ]
        };
        if eye.iter().all(|component| component.is_finite()) {
            return eye;
        }
    }
    let panel_forward = surface_particle_panel_forward(panel_projection);
    add3(
        add3(
            panel_projection.center,
            scale3(panel_forward, -panel_projection.target_distance_meters),
        ),
        scale3(
            panel_projection.right,
            surface_particle_panel_eye_offset_right_meters(panel_projection, eye_index),
        ),
    )
}

fn surface_particle_default_openxr_view() -> openxr_sys::View {
    openxr_sys::View {
        ty: openxr_sys::View::TYPE,
        next: ptr::null_mut(),
        pose: openxr_sys::Posef {
            orientation: openxr_sys::Quaternionf {
                x: 0.0,
                y: 0.0,
                z: 0.0,
                w: 1.0,
            },
            position: openxr_sys::Vector3f::default(),
        },
        fov: openxr_sys::Fovf {
            angle_left: 0.0,
            angle_right: 0.0,
            angle_up: 0.0,
            angle_down: 0.0,
        },
    }
}

unsafe fn surface_particle_create_reference_space(
    session: openxr_sys::Session,
    create_reference_space: openxr_sys::pfn::CreateReferenceSpace,
) -> Result<(openxr_sys::Space, &'static str), String> {
    match surface_particle_create_reference_space_of_type(
        session,
        create_reference_space,
        openxr_sys::ReferenceSpaceType::LOCAL_FLOOR,
    ) {
        Ok(space) => Ok((space, "LOCAL_FLOOR")),
        Err(local_floor_error) => surface_particle_create_reference_space_of_type(
            session,
            create_reference_space,
            openxr_sys::ReferenceSpaceType::LOCAL,
        )
        .map(|space| (space, "LOCAL"))
        .map_err(|local_error| format!("{local_floor_error}_fallback-{local_error}")),
    }
}

unsafe fn surface_particle_create_reference_space_of_type(
    session: openxr_sys::Session,
    create_reference_space: openxr_sys::pfn::CreateReferenceSpace,
    reference_space_type: openxr_sys::ReferenceSpaceType,
) -> Result<openxr_sys::Space, String> {
    let create_info = openxr_sys::ReferenceSpaceCreateInfo {
        ty: openxr_sys::ReferenceSpaceCreateInfo::TYPE,
        next: ptr::null(),
        reference_space_type,
        pose_in_reference_space: openxr_sys::Posef {
            orientation: openxr_sys::Quaternionf {
                x: 0.0,
                y: 0.0,
                z: 0.0,
                w: 1.0,
            },
            position: openxr_sys::Vector3f {
                x: 0.0,
                y: 0.0,
                z: 0.0,
            },
        },
    };
    let mut space = openxr_sys::Space::NULL;
    let result = create_reference_space(session, &create_info, &mut space);
    if result == openxr_sys::Result::SUCCESS && space != openxr_sys::Space::NULL {
        Ok(space)
    } else {
        Err(format!(
            "xrCreateReferenceSpace-{}-{}",
            surface_particle_reference_space_token(reference_space_type),
            external_swapchain_xr_result_token(result)
        ))
    }
}

fn surface_particle_reference_space_token(
    reference_space_type: openxr_sys::ReferenceSpaceType,
) -> &'static str {
    if reference_space_type == openxr_sys::ReferenceSpaceType::LOCAL_FLOOR {
        "LOCAL_FLOOR"
    } else if reference_space_type == openxr_sys::ReferenceSpaceType::LOCAL {
        "LOCAL"
    } else if reference_space_type == openxr_sys::ReferenceSpaceType::STAGE {
        "STAGE"
    } else {
        "unknown"
    }
}

struct SurfaceParticleSwapchainCarrier {
    loader: ash::khr::swapchain::Device,
    swapchain: vk::SwapchainKHR,
    images: Vec<vk::Image>,
    format: vk::Format,
    extent: vk::Extent2D,
}

impl SurfaceParticleSwapchainCarrier {
    unsafe fn create(
        instance: &ash::Instance,
        device: &ash::Device,
        surface_loader: &ash::khr::surface::Instance,
        surface: vk::SurfaceKHR,
        physical_device: vk::PhysicalDevice,
        requested_width: u32,
        requested_height: u32,
    ) -> Result<Self, String> {
        let loader = ash::khr::swapchain::Device::new(instance, device);
        let surface_format = choose_surface_format(
            &surface_loader
                .get_physical_device_surface_formats(physical_device, surface)
                .map_err(|error| format!("surface-formats-{error:?}"))?,
        );
        let capabilities = surface_loader
            .get_physical_device_surface_capabilities(physical_device, surface)
            .map_err(|error| format!("surface-capabilities-{error:?}"))?;
        let present_modes = surface_loader
            .get_physical_device_surface_present_modes(physical_device, surface)
            .unwrap_or_default();
        let present_mode = choose_present_mode(&present_modes);
        let extent = choose_extent(&capabilities, requested_width, requested_height);
        let image_count = choose_image_count(&capabilities);
        let composite_alpha = choose_composite_alpha(capabilities.supported_composite_alpha);
        let swapchain_info = vk::SwapchainCreateInfoKHR::default()
            .surface(surface)
            .min_image_count(image_count)
            .image_format(surface_format.format)
            .image_color_space(surface_format.color_space)
            .image_extent(extent)
            .image_array_layers(1)
            .image_usage(vk::ImageUsageFlags::COLOR_ATTACHMENT)
            .image_sharing_mode(vk::SharingMode::EXCLUSIVE)
            .pre_transform(capabilities.current_transform)
            .composite_alpha(composite_alpha)
            .present_mode(present_mode)
            .clipped(true);
        let swapchain = loader
            .create_swapchain(&swapchain_info, None)
            .map_err(|error| format!("create-swapchain-{error:?}"))?;
        let images = match loader.get_swapchain_images(swapchain) {
            Ok(images) => images,
            Err(error) => {
                loader.destroy_swapchain(swapchain, None);
                return Err(format!("swapchain-images-{error:?}"));
            }
        };
        Ok(Self {
            loader,
            swapchain,
            images,
            format: surface_format.format,
            extent,
        })
    }

    fn format(&self) -> vk::Format {
        self.format
    }

    fn extent(&self) -> vk::Extent2D {
        self.extent
    }

    fn images(&self) -> &[vk::Image] {
        &self.images
    }

    fn image_count(&self) -> usize {
        self.images.len()
    }

    unsafe fn acquire_next_image(
        &self,
        image_available: vk::Semaphore,
    ) -> Result<Option<u32>, String> {
        match self.loader.acquire_next_image(
            self.swapchain,
            u64::MAX,
            image_available,
            vk::Fence::null(),
        ) {
            Ok((image_index, _suboptimal)) => Ok(Some(image_index)),
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => Ok(None),
            Err(error) => Err(format!("acquire-next-image-{error:?}")),
        }
    }

    unsafe fn present(
        &self,
        queue: vk::Queue,
        image_index: u32,
        render_finished: vk::Semaphore,
    ) -> Result<bool, String> {
        let wait_semaphores = [render_finished];
        let swapchains = [self.swapchain];
        let image_indices = [image_index];
        let present_info = vk::PresentInfoKHR::default()
            .wait_semaphores(&wait_semaphores)
            .swapchains(&swapchains)
            .image_indices(&image_indices);
        match self.loader.queue_present(queue, &present_info) {
            Ok(_suboptimal) => Ok(true),
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => Ok(false),
            Err(error) => Err(format!("queue-present-{error:?}")),
        }
    }

    unsafe fn destroy(&mut self) {
        if self.swapchain != vk::SwapchainKHR::null() {
            self.loader.destroy_swapchain(self.swapchain, None);
            self.swapchain = vk::SwapchainKHR::null();
        }
    }
}

struct SurfaceParticleFrameLoopResources {
    command_pool: vk::CommandPool,
    command_buffers: Vec<vk::CommandBuffer>,
    image_available: vk::Semaphore,
    render_finished: vk::Semaphore,
    frame_fence: vk::Fence,
}

impl SurfaceParticleFrameLoopResources {
    unsafe fn create(
        device: &ash::Device,
        queue_family_index: u32,
        image_count: usize,
    ) -> Result<Self, String> {
        let command_pool_info = vk::CommandPoolCreateInfo::default()
            .queue_family_index(queue_family_index)
            .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER);
        let command_pool = device
            .create_command_pool(&command_pool_info, None)
            .map_err(|error| format!("create-command-pool-{error:?}"))?;
        let command_allocate_info = vk::CommandBufferAllocateInfo::default()
            .command_pool(command_pool)
            .level(vk::CommandBufferLevel::PRIMARY)
            .command_buffer_count(image_count as u32);
        let command_buffers = match device.allocate_command_buffers(&command_allocate_info) {
            Ok(command_buffers) => command_buffers,
            Err(error) => {
                device.destroy_command_pool(command_pool, None);
                return Err(format!("allocate-command-buffers-{error:?}"));
            }
        };
        let semaphore_info = vk::SemaphoreCreateInfo::default();
        let image_available = match device.create_semaphore(&semaphore_info, None) {
            Ok(semaphore) => semaphore,
            Err(error) => {
                device.destroy_command_pool(command_pool, None);
                return Err(format!("create-image-semaphore-{error:?}"));
            }
        };
        let render_finished = match device.create_semaphore(&semaphore_info, None) {
            Ok(semaphore) => semaphore,
            Err(error) => {
                device.destroy_semaphore(image_available, None);
                device.destroy_command_pool(command_pool, None);
                return Err(format!("create-render-semaphore-{error:?}"));
            }
        };
        let fence_info = vk::FenceCreateInfo::default().flags(vk::FenceCreateFlags::SIGNALED);
        let frame_fence = match device.create_fence(&fence_info, None) {
            Ok(fence) => fence,
            Err(error) => {
                device.destroy_semaphore(render_finished, None);
                device.destroy_semaphore(image_available, None);
                device.destroy_command_pool(command_pool, None);
                return Err(format!("create-frame-fence-{error:?}"));
            }
        };
        Ok(Self {
            command_pool,
            command_buffers,
            image_available,
            render_finished,
            frame_fence,
        })
    }

    fn image_available(&self) -> vk::Semaphore {
        self.image_available
    }

    fn render_finished(&self) -> vk::Semaphore {
        self.render_finished
    }

    fn command_buffer(&self, image_index: u32) -> vk::CommandBuffer {
        self.command_buffers[image_index as usize]
    }

    unsafe fn wait_and_reset_fence(&self, device: &ash::Device) -> Result<(), String> {
        device
            .wait_for_fences(&[self.frame_fence], true, u64::MAX)
            .map_err(|error| format!("wait-fence-{error:?}"))?;
        device
            .reset_fences(&[self.frame_fence])
            .map_err(|error| format!("reset-fence-{error:?}"))
    }

    unsafe fn submit(
        &self,
        device: &ash::Device,
        queue: vk::Queue,
        command_buffer: vk::CommandBuffer,
    ) -> Result<(), String> {
        let wait_semaphores = [self.image_available];
        let wait_stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
        let signal_semaphores = [self.render_finished];
        let submit_command_buffers = [command_buffer];
        let submit_info = [vk::SubmitInfo::default()
            .wait_semaphores(&wait_semaphores)
            .wait_dst_stage_mask(&wait_stages)
            .command_buffers(&submit_command_buffers)
            .signal_semaphores(&signal_semaphores)];
        device
            .queue_submit(queue, &submit_info, self.frame_fence)
            .map_err(|error| format!("queue-submit-{error:?}"))
    }

    unsafe fn destroy(&mut self, device: &ash::Device) {
        if self.frame_fence != vk::Fence::null() {
            device.destroy_fence(self.frame_fence, None);
            self.frame_fence = vk::Fence::null();
        }
        if self.render_finished != vk::Semaphore::null() {
            device.destroy_semaphore(self.render_finished, None);
            self.render_finished = vk::Semaphore::null();
        }
        if self.image_available != vk::Semaphore::null() {
            device.destroy_semaphore(self.image_available, None);
            self.image_available = vk::Semaphore::null();
        }
        if self.command_pool != vk::CommandPool::null() {
            device.destroy_command_pool(self.command_pool, None);
            self.command_pool = vk::CommandPool::null();
        }
        self.command_buffers.clear();
    }
}

struct SurfaceParticlePipelineResources {
    descriptor_set_layout: vk::DescriptorSetLayout,
    pipeline_layout: vk::PipelineLayout,
    graphics_pipeline: vk::Pipeline,
    compute_pipeline: vk::Pipeline,
}

impl SurfaceParticlePipelineResources {
    unsafe fn create(device: &ash::Device, render_pass: vk::RenderPass) -> Result<Self, String> {
        let descriptor_set_layout = create_descriptor_set_layout(device)?;
        let pipeline_layout = match create_pipeline_layout(device, descriptor_set_layout) {
            Ok(pipeline_layout) => pipeline_layout,
            Err(error) => {
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(error);
            }
        };
        let graphics_pipeline = match create_graphics_pipeline(device, render_pass, pipeline_layout)
        {
            Ok(graphics_pipeline) => graphics_pipeline,
            Err(error) => {
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(error);
            }
        };
        let compute_pipeline = match create_compute_pipeline(device, pipeline_layout) {
            Ok(compute_pipeline) => compute_pipeline,
            Err(error) => {
                device.destroy_pipeline(graphics_pipeline, None);
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(error);
            }
        };
        Ok(Self {
            descriptor_set_layout,
            pipeline_layout,
            graphics_pipeline,
            compute_pipeline,
        })
    }

    fn descriptor_set_layout(&self) -> vk::DescriptorSetLayout {
        self.descriptor_set_layout
    }

    fn pipeline_layout(&self) -> vk::PipelineLayout {
        self.pipeline_layout
    }

    fn graphics_pipeline(&self) -> vk::Pipeline {
        self.graphics_pipeline
    }

    fn compute_pipeline(&self) -> vk::Pipeline {
        self.compute_pipeline
    }

    unsafe fn destroy(&mut self, device: &ash::Device) {
        if self.compute_pipeline != vk::Pipeline::null() {
            device.destroy_pipeline(self.compute_pipeline, None);
            self.compute_pipeline = vk::Pipeline::null();
        }
        if self.graphics_pipeline != vk::Pipeline::null() {
            device.destroy_pipeline(self.graphics_pipeline, None);
            self.graphics_pipeline = vk::Pipeline::null();
        }
        if self.pipeline_layout != vk::PipelineLayout::null() {
            device.destroy_pipeline_layout(self.pipeline_layout, None);
            self.pipeline_layout = vk::PipelineLayout::null();
        }
        if self.descriptor_set_layout != vk::DescriptorSetLayout::null() {
            device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
            self.descriptor_set_layout = vk::DescriptorSetLayout::null();
        }
    }
}

struct SurfaceParticleDescriptorResources {
    descriptor_pool: vk::DescriptorPool,
    descriptor_set: vk::DescriptorSet,
}

impl SurfaceParticleDescriptorResources {
    unsafe fn create(
        device: &ash::Device,
        descriptor_set_layout: vk::DescriptorSetLayout,
        particle_buffer: vk::Buffer,
        particle_buffer_size: vk::DeviceSize,
    ) -> Result<Self, String> {
        let descriptor_pool = create_descriptor_pool(device)?;
        let descriptor_set = match create_particle_descriptor_set(
            device,
            descriptor_pool,
            descriptor_set_layout,
            particle_buffer,
            particle_buffer_size,
        ) {
            Ok(descriptor_set) => descriptor_set,
            Err(error) => {
                device.destroy_descriptor_pool(descriptor_pool, None);
                return Err(error);
            }
        };
        Ok(Self {
            descriptor_pool,
            descriptor_set,
        })
    }

    fn descriptor_set(&self) -> vk::DescriptorSet {
        self.descriptor_set
    }

    unsafe fn destroy(&mut self, device: &ash::Device) {
        if self.descriptor_pool != vk::DescriptorPool::null() {
            device.destroy_descriptor_pool(self.descriptor_pool, None);
            self.descriptor_pool = vk::DescriptorPool::null();
        }
        self.descriptor_set = vk::DescriptorSet::null();
    }
}

struct SurfaceParticlePrivatePayloadGpuResources {
    positions_buffer: vk::Buffer,
    positions_memory: vk::DeviceMemory,
    normals_buffer: vk::Buffer,
    normals_memory: vk::DeviceMemory,
    aux0_buffer: vk::Buffer,
    aux0_memory: vk::DeviceMemory,
    mask_texture: SurfaceParticlePrivateMaskTexture,
    positions_byte_count: vk::DeviceSize,
    normals_byte_count: vk::DeviceSize,
    aux0_byte_count: vk::DeviceSize,
    mask_texture_byte_count: vk::DeviceSize,
    upload_byte_count: vk::DeviceSize,
}

impl SurfaceParticlePrivatePayloadGpuResources {
    unsafe fn create_if_staged(
        instance: &ash::Instance,
        device: &ash::Device,
        physical_device: vk::PhysicalDevice,
        queue: vk::Queue,
        command_pool: vk::CommandPool,
        build_config: SurfaceParticlePrivateBuildConfig,
    ) -> Result<Option<Self>, String> {
        if !build_config.staged_payload_ready {
            return Ok(None);
        }
        let (positions_buffer, positions_memory) = create_private_payload_buffer(
            instance,
            device,
            physical_device,
            surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_POSITIONS_BYTES,
            "positions",
        )?;
        let (normals_buffer, normals_memory) = match create_private_payload_buffer(
            instance,
            device,
            physical_device,
            surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_NORMALS_BYTES,
            "normals",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                device.destroy_buffer(positions_buffer, None);
                device.free_memory(positions_memory, None);
                return Err(error);
            }
        };
        let (aux0_buffer, aux0_memory) = match create_private_payload_buffer(
            instance,
            device,
            physical_device,
            surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_AUX0_BYTES,
            "aux0",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                device.destroy_buffer(normals_buffer, None);
                device.free_memory(normals_memory, None);
                device.destroy_buffer(positions_buffer, None);
                device.free_memory(positions_memory, None);
                return Err(error);
            }
        };
        let mask_texture = match SurfaceParticlePrivateMaskTexture::create(
            instance,
            device,
            physical_device,
            queue,
            command_pool,
            surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_MASK_TEXTURE_BYTES,
        ) {
            Ok(texture) => texture,
            Err(error) => {
                device.destroy_buffer(aux0_buffer, None);
                device.free_memory(aux0_memory, None);
                device.destroy_buffer(normals_buffer, None);
                device.free_memory(normals_memory, None);
                device.destroy_buffer(positions_buffer, None);
                device.free_memory(positions_memory, None);
                return Err(error);
            }
        };
        Ok(Some(Self {
            positions_buffer,
            positions_memory,
            normals_buffer,
            normals_memory,
            aux0_buffer,
            aux0_memory,
            mask_texture,
            positions_byte_count: build_config.positions_byte_count as vk::DeviceSize,
            normals_byte_count: build_config.normals_byte_count as vk::DeviceSize,
            aux0_byte_count: build_config.aux0_byte_count as vk::DeviceSize,
            mask_texture_byte_count: build_config.mask_texture_byte_count as vk::DeviceSize,
            upload_byte_count: build_config.positions_byte_count as vk::DeviceSize
                + build_config.normals_byte_count as vk::DeviceSize
                + build_config.aux0_byte_count as vk::DeviceSize
                + build_config.mask_texture_byte_count as vk::DeviceSize,
        }))
    }

    fn marker_fields(&self) -> String {
        format!(
            "privateSurfaceParticleStagedGpuBuffersResident=true privateSurfaceParticlePayloadGpuUploadBytes={} privateSurfaceParticleMaskTextureGpuBytes={} privateSurfaceParticlePayloadBufferMode=storage-buffers-plus-r8-mask-texture-array privateSurfaceParticleMaskTextureFormat=R8_UNORM privateSurfaceParticleMaskTextureMode=texture-array-blend privateSurfaceParticleMaskTextureSize={}x{}x{} privateSurfaceParticleMaskTextureGpuResident=true privateSurfaceParticlePayloadBoundToPrivateRenderer=true",
            self.upload_byte_count,
            self.mask_texture_byte_count,
            self.mask_texture.width,
            self.mask_texture.height,
            self.mask_texture.layers,
        )
    }

    fn positions_descriptor(&self) -> vk::DescriptorBufferInfo {
        vk::DescriptorBufferInfo::default()
            .buffer(self.positions_buffer)
            .offset(0)
            .range(self.positions_byte_count)
    }

    fn normals_descriptor(&self) -> vk::DescriptorBufferInfo {
        vk::DescriptorBufferInfo::default()
            .buffer(self.normals_buffer)
            .offset(0)
            .range(self.normals_byte_count)
    }

    fn aux0_descriptor(&self) -> vk::DescriptorBufferInfo {
        vk::DescriptorBufferInfo::default()
            .buffer(self.aux0_buffer)
            .offset(0)
            .range(self.aux0_byte_count)
    }

    fn mask_texture_descriptor(&self) -> vk::DescriptorImageInfo {
        self.mask_texture.descriptor()
    }

    unsafe fn destroy(&mut self, device: &ash::Device) {
        self.mask_texture.destroy(device);
        if self.aux0_buffer != vk::Buffer::null() {
            device.destroy_buffer(self.aux0_buffer, None);
            self.aux0_buffer = vk::Buffer::null();
        }
        if self.aux0_memory != vk::DeviceMemory::null() {
            device.free_memory(self.aux0_memory, None);
            self.aux0_memory = vk::DeviceMemory::null();
        }
        if self.normals_buffer != vk::Buffer::null() {
            device.destroy_buffer(self.normals_buffer, None);
            self.normals_buffer = vk::Buffer::null();
        }
        if self.normals_memory != vk::DeviceMemory::null() {
            device.free_memory(self.normals_memory, None);
            self.normals_memory = vk::DeviceMemory::null();
        }
        if self.positions_buffer != vk::Buffer::null() {
            device.destroy_buffer(self.positions_buffer, None);
            self.positions_buffer = vk::Buffer::null();
        }
        if self.positions_memory != vk::DeviceMemory::null() {
            device.free_memory(self.positions_memory, None);
            self.positions_memory = vk::DeviceMemory::null();
        }
    }
}

struct SurfaceParticlePrivateMaskTexture {
    image: vk::Image,
    memory: vk::DeviceMemory,
    view: vk::ImageView,
    sampler: vk::Sampler,
    width: u32,
    height: u32,
    layers: u32,
}

impl SurfaceParticlePrivateMaskTexture {
    unsafe fn create(
        instance: &ash::Instance,
        device: &ash::Device,
        physical_device: vk::PhysicalDevice,
        queue: vk::Queue,
        command_pool: vk::CommandPool,
        bytes: &[u8],
    ) -> Result<Self, String> {
        let width = surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_MASK_TEXTURE_WIDTH;
        let height = surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_MASK_TEXTURE_HEIGHT;
        let layers = surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_MASK_TEXTURE_LAYERS;
        let expected_len = width as usize * height as usize * layers as usize;
        if bytes.len() != expected_len {
            return Err(format!(
                "private-surface-particle-mask-texture-bytes-{}-expected-{}-for-{}x{}x{}",
                bytes.len(),
                expected_len,
                width,
                height,
                layers,
            ));
        }

        let staging_size = bytes.len() as vk::DeviceSize;
        let (staging_buffer, staging_memory) = create_buffer_with_memory(
            instance,
            device,
            physical_device,
            staging_size,
            vk::BufferUsageFlags::TRANSFER_SRC,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
        )?;
        let mapped =
            match device.map_memory(staging_memory, 0, staging_size, vk::MemoryMapFlags::empty()) {
                Ok(mapped) => mapped,
                Err(error) => {
                    device.destroy_buffer(staging_buffer, None);
                    device.free_memory(staging_memory, None);
                    return Err(format!(
                        "map-private-surface-particle-mask-staging-{error:?}"
                    ));
                }
            };
        ptr::copy_nonoverlapping(bytes.as_ptr(), mapped.cast::<u8>(), bytes.len());
        device.unmap_memory(staging_memory);

        let image = match device.create_image(
            &vk::ImageCreateInfo::default()
                .image_type(vk::ImageType::TYPE_2D)
                .format(vk::Format::R8_UNORM)
                .extent(vk::Extent3D {
                    width,
                    height,
                    depth: 1,
                })
                .mip_levels(1)
                .array_layers(layers)
                .samples(vk::SampleCountFlags::TYPE_1)
                .tiling(vk::ImageTiling::OPTIMAL)
                .usage(vk::ImageUsageFlags::TRANSFER_DST | vk::ImageUsageFlags::SAMPLED)
                .sharing_mode(vk::SharingMode::EXCLUSIVE)
                .initial_layout(vk::ImageLayout::UNDEFINED),
            None,
        ) {
            Ok(image) => image,
            Err(error) => {
                device.destroy_buffer(staging_buffer, None);
                device.free_memory(staging_memory, None);
                return Err(format!(
                    "create-private-surface-particle-mask-image-{error:?}"
                ));
            }
        };
        let requirements = device.get_image_memory_requirements(image);
        let memory_type_index = match find_memory_type_index(
            instance,
            physical_device,
            requirements.memory_type_bits,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
        ) {
            Some(index) => index,
            None => {
                device.destroy_image(image, None);
                device.destroy_buffer(staging_buffer, None);
                device.free_memory(staging_memory, None);
                return Err("no-memory-type-for-private-surface-particle-mask-image".to_string());
            }
        };
        let memory = match device.allocate_memory(
            &vk::MemoryAllocateInfo::default()
                .allocation_size(requirements.size)
                .memory_type_index(memory_type_index),
            None,
        ) {
            Ok(memory) => memory,
            Err(error) => {
                device.destroy_image(image, None);
                device.destroy_buffer(staging_buffer, None);
                device.free_memory(staging_memory, None);
                return Err(format!(
                    "allocate-private-surface-particle-mask-image-memory-{error:?}"
                ));
            }
        };
        if let Err(error) = device.bind_image_memory(image, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            device.destroy_buffer(staging_buffer, None);
            device.free_memory(staging_memory, None);
            return Err(format!(
                "bind-private-surface-particle-mask-image-memory-{error:?}"
            ));
        }
        if let Err(error) = upload_private_surface_particle_mask_texture(
            device,
            queue,
            command_pool,
            staging_buffer,
            image,
            width,
            height,
            layers,
        ) {
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            device.destroy_buffer(staging_buffer, None);
            device.free_memory(staging_memory, None);
            return Err(error);
        }
        device.destroy_buffer(staging_buffer, None);
        device.free_memory(staging_memory, None);

        let view = match device.create_image_view(
            &vk::ImageViewCreateInfo::default()
                .image(image)
                .view_type(vk::ImageViewType::TYPE_2D_ARRAY)
                .format(vk::Format::R8_UNORM)
                .subresource_range(private_surface_particle_mask_subresource_range(layers)),
            None,
        ) {
            Ok(view) => view,
            Err(error) => {
                device.free_memory(memory, None);
                device.destroy_image(image, None);
                return Err(format!(
                    "create-private-surface-particle-mask-image-view-{error:?}"
                ));
            }
        };
        let sampler = match device.create_sampler(
            &vk::SamplerCreateInfo::default()
                .mag_filter(vk::Filter::LINEAR)
                .min_filter(vk::Filter::LINEAR)
                .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
                .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .min_lod(0.0)
                .max_lod(0.0),
            None,
        ) {
            Ok(sampler) => sampler,
            Err(error) => {
                device.destroy_image_view(view, None);
                device.free_memory(memory, None);
                device.destroy_image(image, None);
                return Err(format!(
                    "create-private-surface-particle-mask-sampler-{error:?}"
                ));
            }
        };

        Ok(Self {
            image,
            memory,
            view,
            sampler,
            width,
            height,
            layers,
        })
    }

    fn descriptor(&self) -> vk::DescriptorImageInfo {
        vk::DescriptorImageInfo::default()
            .sampler(self.sampler)
            .image_view(self.view)
            .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
    }

    unsafe fn destroy(&mut self, device: &ash::Device) {
        if self.sampler != vk::Sampler::null() {
            device.destroy_sampler(self.sampler, None);
            self.sampler = vk::Sampler::null();
        }
        if self.view != vk::ImageView::null() {
            device.destroy_image_view(self.view, None);
            self.view = vk::ImageView::null();
        }
        if self.image != vk::Image::null() {
            device.destroy_image(self.image, None);
            self.image = vk::Image::null();
        }
        if self.memory != vk::DeviceMemory::null() {
            device.free_memory(self.memory, None);
            self.memory = vk::DeviceMemory::null();
        }
    }
}

struct SurfaceParticlePrivatePipelineResources {
    descriptor_set_layout: vk::DescriptorSetLayout,
    pipeline_layout: vk::PipelineLayout,
    compute_pipeline: vk::Pipeline,
    graphics_pipeline: vk::Pipeline,
}

impl SurfaceParticlePrivatePipelineResources {
    unsafe fn create_if_staged(
        device: &ash::Device,
        render_pass: vk::RenderPass,
        build_config: SurfaceParticlePrivateBuildConfig,
    ) -> Result<Option<Self>, String> {
        if !build_config.staged_payload_ready {
            return Ok(None);
        }
        let descriptor_set_layout = create_private_surface_particle_descriptor_set_layout(device)?;
        let pipeline_layout =
            match create_private_surface_particle_pipeline_layout(device, descriptor_set_layout) {
                Ok(pipeline_layout) => pipeline_layout,
                Err(error) => {
                    device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                    return Err(error);
                }
            };
        let compute_pipeline =
            match create_private_surface_particle_compute_pipeline(device, pipeline_layout) {
                Ok(compute_pipeline) => compute_pipeline,
                Err(error) => {
                    device.destroy_pipeline_layout(pipeline_layout, None);
                    device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                    return Err(error);
                }
            };
        let graphics_pipeline = match create_private_surface_particle_graphics_pipeline(
            device,
            render_pass,
            pipeline_layout,
        ) {
            Ok(graphics_pipeline) => graphics_pipeline,
            Err(error) => {
                device.destroy_pipeline(compute_pipeline, None);
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(error);
            }
        };
        Ok(Some(Self {
            descriptor_set_layout,
            pipeline_layout,
            compute_pipeline,
            graphics_pipeline,
        }))
    }

    fn marker_fields(&self) -> &'static str {
        "privateSurfaceParticlePrivateComputePipelineReady=true privateSurfaceParticlePrivateGraphicsPipelineReady=true privateSurfaceParticlePrivateDescriptorBindingPlan=0;1;2;3;4;5;6;8;9 privateSurfaceParticlePrivatePushBytes=128 privateSurfaceParticlePrivateDispatchEnabled=true privateSurfaceParticlePrivateDrawEnabled=true privateSurfaceParticlePrivateTracerDispatchEnabled=true privateSurfaceParticlePrivateTracerDrawEnabled=true"
    }

    fn descriptor_set_layout(&self) -> vk::DescriptorSetLayout {
        self.descriptor_set_layout
    }

    fn pipeline_layout(&self) -> vk::PipelineLayout {
        self.pipeline_layout
    }

    fn compute_pipeline(&self) -> vk::Pipeline {
        self.compute_pipeline
    }

    fn graphics_pipeline(&self) -> vk::Pipeline {
        self.graphics_pipeline
    }

    unsafe fn destroy(&mut self, device: &ash::Device) {
        if self.graphics_pipeline != vk::Pipeline::null() {
            device.destroy_pipeline(self.graphics_pipeline, None);
            self.graphics_pipeline = vk::Pipeline::null();
        }
        if self.compute_pipeline != vk::Pipeline::null() {
            device.destroy_pipeline(self.compute_pipeline, None);
            self.compute_pipeline = vk::Pipeline::null();
        }
        if self.pipeline_layout != vk::PipelineLayout::null() {
            device.destroy_pipeline_layout(self.pipeline_layout, None);
            self.pipeline_layout = vk::PipelineLayout::null();
        }
        if self.descriptor_set_layout != vk::DescriptorSetLayout::null() {
            device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
            self.descriptor_set_layout = vk::DescriptorSetLayout::null();
        }
    }
}

struct SurfaceParticlePrivateBuffer {
    buffer: vk::Buffer,
    memory: vk::DeviceMemory,
    bytes: vk::DeviceSize,
}

impl SurfaceParticlePrivateBuffer {
    fn descriptor(&self) -> vk::DescriptorBufferInfo {
        vk::DescriptorBufferInfo::default()
            .buffer(self.buffer)
            .offset(0)
            .range(self.bytes)
    }

    unsafe fn destroy(&mut self, device: &ash::Device) {
        if self.buffer != vk::Buffer::null() {
            device.destroy_buffer(self.buffer, None);
            self.buffer = vk::Buffer::null();
        }
        if self.memory != vk::DeviceMemory::null() {
            device.free_memory(self.memory, None);
            self.memory = vk::DeviceMemory::null();
        }
    }
}

struct SurfaceParticlePrivateDescriptorResources {
    descriptor_pool: vk::DescriptorPool,
    descriptor_sets: [vk::DescriptorSet; PRIVATE_SURFACE_PARTICLE_DESCRIPTOR_SET_COUNT],
    particle_output_buffer: SurfaceParticlePrivateBuffer,
    phase_buffer_a: SurfaceParticlePrivateBuffer,
    phase_buffer_b: SurfaceParticlePrivateBuffer,
    driver_bank_buffer: SurfaceParticlePrivateBuffer,
    diagnostic_buffer_a: SurfaceParticlePrivateBuffer,
    diagnostic_buffer_b: SurfaceParticlePrivateBuffer,
    particle_count: u64,
    tracer_state_capacity: u64,
    tracer_draw_count: u64,
    retention_indicator_draw_count: u64,
    draw_count: u64,
    particle_output_rows: u64,
    phase_state_rows: u64,
    descriptor_buffer_bytes: vk::DeviceSize,
    driver_bank_revision: u32,
}

impl SurfaceParticlePrivateDescriptorResources {
    unsafe fn create_if_ready(
        instance: &ash::Instance,
        device: &ash::Device,
        physical_device: vk::PhysicalDevice,
        build_config: SurfaceParticlePrivateBuildConfig,
        payload_resources: Option<&SurfaceParticlePrivatePayloadGpuResources>,
        pipeline_resources: Option<&SurfaceParticlePrivatePipelineResources>,
    ) -> Result<Option<Self>, String> {
        let (payload_resources, pipeline_resources) = match (payload_resources, pipeline_resources)
        {
            (Some(payload_resources), Some(pipeline_resources))
                if build_config.staged_payload_ready =>
            {
                (payload_resources, pipeline_resources)
            }
            _ => return Ok(None),
        };
        let particle_count = build_config.particle_count();
        let tracer_state_capacity = build_config.tracer_state_capacity();
        let tracer_draw_count = build_config.tracer_draw_count();
        let retention_indicator_draw_count = build_config.retention_indicator_draw_count();
        let draw_count = build_config.draw_count().max(particle_count);
        let particle_output_rows = draw_count * PRIVATE_SURFACE_PARTICLE_OUTPUT_ROWS_PER_INSTANCE;
        let phase_state_rows = particle_count
            * PRIVATE_SURFACE_PARTICLE_MAIN_STATE_ROWS_PER_INSTANCE
            + tracer_state_capacity * PRIVATE_SURFACE_PARTICLE_TRACER_STATE_ROWS_PER_SLOT;
        let particle_output_bytes = particle_output_rows * mem::size_of::<[f32; 4]>() as u64;
        let phase_state_bytes = phase_state_rows * mem::size_of::<[f32; 4]>() as u64;
        let driver_bank_bytes =
            PRIVATE_SURFACE_PARTICLE_DRIVER_BANK_VEC4_ROWS * mem::size_of::<[f32; 4]>() as u64;
        let diagnostic_bytes =
            PRIVATE_SURFACE_PARTICLE_DIAGNOSTIC_WORDS * mem::size_of::<i32>() as u64;

        let particle_output_buffer = create_private_zeroed_storage_buffer(
            instance,
            device,
            physical_device,
            particle_output_bytes as vk::DeviceSize,
            "private-surface-particle-main-output",
        )?;
        let phase_buffer_a = match create_private_zeroed_storage_buffer(
            instance,
            device,
            physical_device,
            phase_state_bytes as vk::DeviceSize,
            "private-surface-particle-main-phase-a",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                destroy_private_descriptor_buffers(device, &mut [particle_output_buffer]);
                return Err(error);
            }
        };
        let phase_buffer_b = match create_private_zeroed_storage_buffer(
            instance,
            device,
            physical_device,
            phase_state_bytes as vk::DeviceSize,
            "private-surface-particle-main-phase-b",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                destroy_private_descriptor_buffers(
                    device,
                    &mut [particle_output_buffer, phase_buffer_a],
                );
                return Err(error);
            }
        };
        let driver_bank_buffer = match create_private_zeroed_storage_buffer(
            instance,
            device,
            physical_device,
            driver_bank_bytes as vk::DeviceSize,
            "private-surface-particle-driver-bank",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                destroy_private_descriptor_buffers(
                    device,
                    &mut [particle_output_buffer, phase_buffer_a, phase_buffer_b],
                );
                return Err(error);
            }
        };
        let diagnostic_buffer_a = match create_private_zeroed_storage_buffer(
            instance,
            device,
            physical_device,
            diagnostic_bytes as vk::DeviceSize,
            "private-surface-particle-diagnostic-a",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                destroy_private_descriptor_buffers(
                    device,
                    &mut [
                        particle_output_buffer,
                        phase_buffer_a,
                        phase_buffer_b,
                        driver_bank_buffer,
                    ],
                );
                return Err(error);
            }
        };
        let diagnostic_buffer_b = match create_private_zeroed_storage_buffer(
            instance,
            device,
            physical_device,
            diagnostic_bytes as vk::DeviceSize,
            "private-surface-particle-diagnostic-b",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                destroy_private_descriptor_buffers(
                    device,
                    &mut [
                        particle_output_buffer,
                        phase_buffer_a,
                        phase_buffer_b,
                        driver_bank_buffer,
                        diagnostic_buffer_a,
                    ],
                );
                return Err(error);
            }
        };
        let descriptor_pool = match create_private_surface_particle_descriptor_pool(device) {
            Ok(pool) => pool,
            Err(error) => {
                destroy_private_descriptor_buffers(
                    device,
                    &mut [
                        particle_output_buffer,
                        phase_buffer_a,
                        phase_buffer_b,
                        driver_bank_buffer,
                        diagnostic_buffer_a,
                        diagnostic_buffer_b,
                    ],
                );
                return Err(error);
            }
        };
        let descriptor_sets = match allocate_private_surface_particle_descriptor_sets(
            device,
            descriptor_pool,
            pipeline_resources.descriptor_set_layout(),
        ) {
            Ok(descriptor_sets) => descriptor_sets,
            Err(error) => {
                device.destroy_descriptor_pool(descriptor_pool, None);
                destroy_private_descriptor_buffers(
                    device,
                    &mut [
                        particle_output_buffer,
                        phase_buffer_a,
                        phase_buffer_b,
                        driver_bank_buffer,
                        diagnostic_buffer_a,
                        diagnostic_buffer_b,
                    ],
                );
                return Err(error);
            }
        };

        update_private_surface_particle_descriptor_set(
            device,
            descriptor_sets[0],
            payload_resources,
            particle_output_buffer.descriptor(),
            phase_buffer_a.descriptor(),
            phase_buffer_b.descriptor(),
            driver_bank_buffer.descriptor(),
            diagnostic_buffer_a.descriptor(),
        );
        update_private_surface_particle_descriptor_set(
            device,
            descriptor_sets[1],
            payload_resources,
            particle_output_buffer.descriptor(),
            phase_buffer_b.descriptor(),
            phase_buffer_a.descriptor(),
            driver_bank_buffer.descriptor(),
            diagnostic_buffer_b.descriptor(),
        );

        Ok(Some(Self {
            descriptor_pool,
            descriptor_sets,
            particle_output_buffer,
            phase_buffer_a,
            phase_buffer_b,
            driver_bank_buffer,
            diagnostic_buffer_a,
            diagnostic_buffer_b,
            particle_count,
            tracer_state_capacity,
            tracer_draw_count,
            retention_indicator_draw_count,
            draw_count,
            particle_output_rows,
            phase_state_rows,
            descriptor_buffer_bytes: particle_output_bytes as vk::DeviceSize
                + phase_state_bytes as vk::DeviceSize * 2
                + driver_bank_bytes as vk::DeviceSize
                + diagnostic_bytes as vk::DeviceSize * 2,
            driver_bank_revision: u32::MAX,
        }))
    }

    fn marker_fields(&self) -> String {
        format!(
            "privateSurfaceParticlePrivateDescriptorSetsReady=true privateSurfaceParticlePrivateDescriptorSetCount={} privateSurfaceParticlePrivateMainOnlyBuffersReady=true privateSurfaceParticlePrivateMainOnly=false privateSurfaceParticlePrivateTracerBuffersReady={} privateSurfaceParticlePrivateParticleCount={} privateSurfaceParticlePrivateTracerStateCapacity={} privateSurfaceParticlePrivateTracerDrawCount={} privateSurfaceParticlePrivateRetentionIndicatorDrawCount={} privateSurfaceParticlePrivateDrawCount={} privateSurfaceParticlePrivateParticleOutputRows={} privateSurfaceParticlePrivatePhaseStateRows={} privateSurfaceParticlePrivateDriverBankRows={} privateSurfaceParticlePrivateDiagnosticWords={} privateSurfaceParticlePrivateDescriptorBufferBytes={} privateSurfaceParticlePrivateDescriptorsBoundToDispatch=true",
            self.descriptor_sets.len(),
            bool_token(self.tracer_state_capacity > 0 || self.tracer_draw_count > 0),
            self.particle_count,
            self.tracer_state_capacity,
            self.tracer_draw_count,
            self.retention_indicator_draw_count,
            self.draw_count,
            self.particle_output_rows,
            self.phase_state_rows,
            PRIVATE_SURFACE_PARTICLE_DRIVER_BANK_VEC4_ROWS,
            PRIVATE_SURFACE_PARTICLE_DIAGNOSTIC_WORDS,
            self.descriptor_buffer_bytes,
        )
    }

    fn compute_ready_marker_fields(&self) -> String {
        format!(
            "privateSurfaceParticleMainComputeDispatchReady=true privateSurfaceParticleMainComputeDispatchRecorded=false privateSurfaceParticleMainComputeDispatchWorkgroups={} privateSurfaceParticleMainComputeDispatchParticleCount={} privateSurfaceParticleMainComputeDispatchDrawCount={} privateSurfaceParticleMainComputeDispatchTracerMaxCount={} privateSurfaceParticleMainComputeDispatchTracerDrawCount={} privateSurfaceParticleMainComputeDispatchRetentionIndicatorDrawCount={} privateSurfaceParticleMainComputeDispatchVisible=true",
            self.compute_workgroups(),
            self.particle_count,
            self.draw_count,
            self.tracer_state_capacity,
            self.tracer_draw_count,
            self.retention_indicator_draw_count,
        )
    }

    fn compute_recorded_marker_fields(&self) -> String {
        format!(
            "privateSurfaceParticleMainComputeDispatchReady=true privateSurfaceParticleMainComputeDispatchRecorded=true privateSurfaceParticleMainComputeDispatchWorkgroups={} privateSurfaceParticleMainComputeDispatchParticleCount={} privateSurfaceParticleMainComputeDispatchDrawCount={} privateSurfaceParticleMainComputeDispatchTracerMaxCount={} privateSurfaceParticleMainComputeDispatchTracerDrawCount={} privateSurfaceParticleMainComputeDispatchRetentionIndicatorDrawCount={} privateSurfaceParticleMainComputeDispatchVisible=true",
            self.compute_workgroups(),
            self.particle_count,
            self.draw_count,
            self.tracer_state_capacity,
            self.tracer_draw_count,
            self.retention_indicator_draw_count,
        )
    }

    fn draw_ready_marker_fields(&self) -> String {
        format!(
            "privateSurfaceParticleMainDrawReady=true privateSurfaceParticleMainDrawRecorded=false privateSurfaceParticleMainDrawVerticesPerInstance={} privateSurfaceParticleMainDrawInstanceCount={} privateSurfaceParticleMainDrawParticleCount={} privateSurfaceParticleMainDrawTracerDrawCount={} privateSurfaceParticleMainDrawRetentionIndicatorDrawCount={} privateSurfaceParticleMainDrawVisible=true privateSurfaceParticleMainDrawProjection=spatial-sdk-world-explicit-viewer-camera-to-panel-plane privateSurfaceParticleMainDrawCameraBasisSource=Scene.getViewerPose-position+forward-x-mirror-corrected-roll-stable privateSurfaceParticleCarrierPanelForwardSource=spatial-sdk-presentation-plane-only privateSurfaceParticleEyePoseSource=Scene.getViewerPose+Scene.getEyeOffsets privateSurfaceParticlePanelPoseSource=Scene.getViewerPose-derived-panel-plane privateSurfaceParticlePanelDefinesEye=false privateSurfaceParticleWorldAnchorCenterSource=configured-spatial-world-coordinate privateSurfaceParticleWorldAnchorBasis=spatial-world-canonical-axes privateSurfaceParticleWorldAnchorScaleSource=fixed-sim-meter-radius privateSurfaceParticleSimRegistration=sim-space-fixed-in-spatial-sdk-world-space privateSurfaceParticleSimTransform=spatial-world-from-sim-fixed-configured-origin-basis-meter-scale privateSurfaceParticleOpenXrViewDrawAuthority=false privateSurfaceParticleCameraRealignEachFrame=false privateSurfaceParticleOffAxisStereoProjection=true privateSurfaceParticleMainDrawTargetDistanceMaxMeters=2.00",
            PRIVATE_SURFACE_PARTICLE_VERTICES_PER_INSTANCE,
            self.draw_count,
            self.particle_count,
            self.tracer_draw_count,
            self.retention_indicator_draw_count,
        )
    }

    fn draw_recorded_marker_fields(&self) -> String {
        format!(
            "privateSurfaceParticleMainDrawReady=true privateSurfaceParticleMainDrawRecorded=true privateSurfaceParticleMainDrawVerticesPerInstance={} privateSurfaceParticleMainDrawInstanceCount={} privateSurfaceParticleMainDrawParticleCount={} privateSurfaceParticleMainDrawTracerDrawCount={} privateSurfaceParticleMainDrawRetentionIndicatorDrawCount={} privateSurfaceParticleMainDrawVisible=true privateSurfaceParticleMainDrawProjection=spatial-sdk-world-explicit-viewer-camera-to-panel-plane privateSurfaceParticleMainDrawCameraBasisSource=Scene.getViewerPose-position+forward-x-mirror-corrected-roll-stable privateSurfaceParticleCarrierPanelForwardSource=spatial-sdk-presentation-plane-only privateSurfaceParticleEyePoseSource=Scene.getViewerPose+Scene.getEyeOffsets privateSurfaceParticlePanelPoseSource=Scene.getViewerPose-derived-panel-plane privateSurfaceParticlePanelDefinesEye=false privateSurfaceParticleWorldAnchorCenterSource=configured-spatial-world-coordinate privateSurfaceParticleWorldAnchorBasis=spatial-world-canonical-axes privateSurfaceParticleWorldAnchorScaleSource=fixed-sim-meter-radius privateSurfaceParticleSimRegistration=sim-space-fixed-in-spatial-sdk-world-space privateSurfaceParticleSimTransform=spatial-world-from-sim-fixed-configured-origin-basis-meter-scale privateSurfaceParticleOpenXrViewDrawAuthority=false privateSurfaceParticleCameraRealignEachFrame=false privateSurfaceParticleOffAxisStereoProjection=true privateSurfaceParticleMainDrawTargetDistanceMaxMeters=2.00",
            PRIVATE_SURFACE_PARTICLE_VERTICES_PER_INSTANCE,
            self.draw_count,
            self.particle_count,
            self.tracer_draw_count,
            self.retention_indicator_draw_count,
        )
    }

    fn compact_ready_marker_fields(&self) -> String {
        format!(
            "privateSurfaceParticlePrivateDescriptorSetsReady=true privateSurfaceParticlePrivateTracerBuffersReady={} privateSurfaceParticlePrivateParticleCount={} privateSurfaceParticlePrivateTracerStateCapacity={} privateSurfaceParticlePrivateTracerDrawCount={} privateSurfaceParticlePrivateDrawCount={} privateSurfaceParticleMainComputeDispatchReady=true privateSurfaceParticleMainComputeDispatchRecorded=false privateSurfaceParticleMainComputeDispatchWorkgroups={} privateSurfaceParticleMainComputeDispatchParticleCount={} privateSurfaceParticleMainComputeDispatchTracerDrawCount={} privateSurfaceParticleMainDrawReady=true privateSurfaceParticleMainDrawRecorded=false privateSurfaceParticleMainDrawParticleCount={} privateSurfaceParticleMainDrawTracerDrawCount={} privateSurfaceParticleMainDrawVisible=true",
            bool_token(self.tracer_state_capacity > 0 || self.tracer_draw_count > 0),
            self.particle_count,
            self.tracer_state_capacity,
            self.tracer_draw_count,
            self.draw_count,
            self.compute_workgroups(),
            self.particle_count,
            self.tracer_draw_count,
            self.particle_count,
            self.tracer_draw_count,
        )
    }

    fn compact_recorded_marker_fields(&self) -> String {
        format!(
            "privateSurfaceParticlePrivateDescriptorSetsReady=true privateSurfaceParticlePrivateTracerBuffersReady={} privateSurfaceParticlePrivateParticleCount={} privateSurfaceParticlePrivateTracerStateCapacity={} privateSurfaceParticlePrivateTracerDrawCount={} privateSurfaceParticlePrivateDrawCount={} privateSurfaceParticleMainComputeDispatchReady=true privateSurfaceParticleMainComputeDispatchRecorded=true privateSurfaceParticleMainComputeDispatchWorkgroups={} privateSurfaceParticleMainComputeDispatchParticleCount={} privateSurfaceParticleMainComputeDispatchTracerDrawCount={} privateSurfaceParticleMainDrawReady=true privateSurfaceParticleMainDrawRecorded=true privateSurfaceParticleMainDrawParticleCount={} privateSurfaceParticleMainDrawTracerDrawCount={} privateSurfaceParticleMainDrawVisible=true",
            bool_token(self.tracer_state_capacity > 0 || self.tracer_draw_count > 0),
            self.particle_count,
            self.tracer_state_capacity,
            self.tracer_draw_count,
            self.draw_count,
            self.compute_workgroups(),
            self.particle_count,
            self.tracer_draw_count,
            self.particle_count,
            self.tracer_draw_count,
        )
    }

    fn compact_counts_ready_marker_fields(&self) -> String {
        format!(
            "privateSurfaceParticleMainComputeDispatchRecorded=false privateSurfaceParticleMainDrawRecorded=false privateSurfaceParticleMainDrawParticleCount={} privateSurfaceParticleMainDrawTracerDrawCount={} privateSurfaceParticlePrivateParticleCount={} privateSurfaceParticlePrivateTracerStateCapacity={} privateSurfaceParticlePrivateTracerDrawCount={} privateSurfaceParticlePrivateDrawCount={} privateSurfaceParticlePrivateDescriptorSetsReady=true privateSurfaceParticlePrivateTracerBuffersReady={}",
            self.particle_count,
            self.tracer_draw_count,
            self.particle_count,
            self.tracer_state_capacity,
            self.tracer_draw_count,
            self.draw_count,
            bool_token(self.tracer_state_capacity > 0 || self.tracer_draw_count > 0),
        )
    }

    fn compact_counts_recorded_marker_fields(&self) -> String {
        format!(
            "privateSurfaceParticleMainComputeDispatchRecorded=true privateSurfaceParticleMainDrawRecorded=true privateSurfaceParticleMainDrawParticleCount={} privateSurfaceParticleMainDrawTracerDrawCount={} privateSurfaceParticlePrivateParticleCount={} privateSurfaceParticlePrivateTracerStateCapacity={} privateSurfaceParticlePrivateTracerDrawCount={} privateSurfaceParticlePrivateDrawCount={} privateSurfaceParticlePrivateDescriptorSetsReady=true privateSurfaceParticlePrivateTracerBuffersReady={}",
            self.particle_count,
            self.tracer_draw_count,
            self.particle_count,
            self.tracer_state_capacity,
            self.tracer_draw_count,
            self.draw_count,
            bool_token(self.tracer_state_capacity > 0 || self.tracer_draw_count > 0),
        )
    }

    fn compute_workgroups(&self) -> u64 {
        self.particle_count
            .saturating_add(SURFACE_PARTICLE_COMPUTE_LOCAL_SIZE as u64 - 1)
            / SURFACE_PARTICLE_COMPUTE_LOCAL_SIZE as u64
    }

    fn effective_tracer_draw_count(&self, params: SurfaceParticleParameters) -> u64 {
        let requested_slots = params
            .tracer_draw_slots_per_oscillator
            .clamp(0.0, 7.0)
            .floor() as u64;
        self.particle_count
            .saturating_mul(requested_slots)
            .min(self.tracer_draw_count)
            .min(self.tracer_state_capacity)
    }

    fn effective_draw_count(&self, params: SurfaceParticleParameters) -> u64 {
        self.particle_count
            .saturating_add(self.effective_tracer_draw_count(params))
            .saturating_add(self.retention_indicator_draw_count)
            .min(self.draw_count)
            .min(u32::MAX as u64)
    }

    unsafe fn update_driver_bank_if_needed(
        &mut self,
        device: &ash::Device,
        params: SurfaceParticleParameters,
    ) {
        if self.driver_bank_revision == params.revision {
            return;
        }
        let mut rows = [[0.0_f32; 4]; PRIVATE_SURFACE_PARTICLE_DRIVER_BANK_VEC4_ROWS as usize];
        rows[0] = [
            params.driver0_value01.clamp(0.0, 1.0),
            params.driver1_value01.clamp(0.0, 1.0),
            params.driver2_value01.clamp(0.0, 1.0),
            params.driver3_value01.clamp(0.0, 1.0),
        ];
        rows[1] = [
            params.driver4_value01.clamp(0.0, 1.0),
            params.driver5_value01.clamp(0.0, 1.0),
            params.driver6_value01.clamp(0.0, 1.0),
            params.driver7_value01.clamp(0.0, 1.0),
        ];
        for slot in 0..PRIVATE_SURFACE_PARTICLE_DRIVER_BANK_SLOT_COUNT as usize {
            let row_index = PRIVATE_SURFACE_PARTICLE_DRIVER_BANK_VALUE_VEC4_ROWS as usize
                + slot * PRIVATE_SURFACE_PARTICLE_DRIVER_CONTROL_ROWS_PER_SLOT as usize;
            rows[row_index][0] = PRIVATE_SURFACE_PARTICLE_DRIVER_MODE_DIRECT;
        }
        let byte_count = mem::size_of_val(&rows).min(self.driver_bank_buffer.bytes as usize);
        let mapped = match device.map_memory(
            self.driver_bank_buffer.memory,
            0,
            self.driver_bank_buffer.bytes,
            vk::MemoryMapFlags::empty(),
        ) {
            Ok(mapped) => mapped,
            Err(error) => {
                android_log_info(
                    "RQSpatialCameraPanelNative",
                    &format!(
                        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=driver-bank-update-failed renderPolicy=native-vulkan-wsi-surface-panel privateSurfaceParticleDriverBankRevision={} error={:?}",
                        params.revision, error
                    ),
                );
                return;
            }
        };
        ptr::copy_nonoverlapping(rows.as_ptr().cast::<u8>(), mapped.cast::<u8>(), byte_count);
        device.unmap_memory(self.driver_bank_buffer.memory);
        self.driver_bank_revision = params.revision;
    }

    unsafe fn record_main_compute_dispatch(
        &mut self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        pipeline_resources: &SurfaceParticlePrivatePipelineResources,
        frame_counter: u32,
        time_seconds: f32,
        params: SurfaceParticleParameters,
        panel_projection: ReplayHandPanelProjection,
    ) {
        let descriptor_index = frame_counter as usize & 1;
        self.update_driver_bank_if_needed(device, params);
        let diagnostic_buffer = if descriptor_index == 0 {
            &self.diagnostic_buffer_a
        } else {
            &self.diagnostic_buffer_b
        };
        device.cmd_fill_buffer(
            command_buffer,
            diagnostic_buffer.buffer,
            0,
            diagnostic_buffer.bytes,
            0,
        );
        let before_dispatch = vk::MemoryBarrier::default()
            .src_access_mask(
                vk::AccessFlags::HOST_WRITE
                    | vk::AccessFlags::TRANSFER_WRITE
                    | vk::AccessFlags::SHADER_WRITE,
            )
            .dst_access_mask(vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE);
        device.cmd_pipeline_barrier(
            command_buffer,
            vk::PipelineStageFlags::HOST
                | vk::PipelineStageFlags::TRANSFER
                | vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::DependencyFlags::empty(),
            slice::from_ref(&before_dispatch),
            &[],
            &[],
        );
        let push = SurfaceParticlePrivatePush::main_compute(
            self.particle_count as u32,
            self.tracer_state_capacity as u32,
            self.effective_draw_count(params) as u32,
            frame_counter,
            time_seconds,
            params,
            panel_projection,
        );
        let push_bytes = slice::from_raw_parts(
            (&push as *const SurfaceParticlePrivatePush).cast::<u8>(),
            mem::size_of::<SurfaceParticlePrivatePush>(),
        );
        device.cmd_bind_pipeline(
            command_buffer,
            vk::PipelineBindPoint::COMPUTE,
            pipeline_resources.compute_pipeline(),
        );
        device.cmd_bind_descriptor_sets(
            command_buffer,
            vk::PipelineBindPoint::COMPUTE,
            pipeline_resources.pipeline_layout(),
            0,
            slice::from_ref(&self.descriptor_sets[descriptor_index]),
            &[],
        );
        device.cmd_push_constants(
            command_buffer,
            pipeline_resources.pipeline_layout(),
            vk::ShaderStageFlags::COMPUTE,
            0,
            push_bytes,
        );
        device.cmd_dispatch(
            command_buffer,
            self.compute_workgroups().max(1) as u32,
            1,
            1,
        );
        let after_dispatch = vk::MemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::SHADER_WRITE)
            .dst_access_mask(vk::AccessFlags::SHADER_READ | vk::AccessFlags::HOST_READ);
        device.cmd_pipeline_barrier(
            command_buffer,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::COMPUTE_SHADER
                | vk::PipelineStageFlags::VERTEX_SHADER
                | vk::PipelineStageFlags::FRAGMENT_SHADER
                | vk::PipelineStageFlags::HOST,
            vk::DependencyFlags::empty(),
            slice::from_ref(&after_dispatch),
            &[],
            &[],
        );
    }

    unsafe fn record_main_draw(
        &self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        pipeline_resources: &SurfaceParticlePrivatePipelineResources,
        frame_counter: u32,
        eye_index: u32,
        time_seconds: f32,
        params: SurfaceParticleParameters,
        panel_projection: ReplayHandPanelProjection,
    ) {
        let descriptor_index = frame_counter as usize & 1;
        let effective_draw_count = self.effective_draw_count(params);
        let push = SurfaceParticlePrivatePush::main_draw(
            self.particle_count as u32,
            self.tracer_state_capacity as u32,
            effective_draw_count as u32,
            frame_counter,
            eye_index,
            time_seconds,
            params,
            panel_projection,
        );
        let push_bytes = slice::from_raw_parts(
            (&push as *const SurfaceParticlePrivatePush).cast::<u8>(),
            mem::size_of::<SurfaceParticlePrivatePush>(),
        );
        device.cmd_bind_pipeline(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            pipeline_resources.graphics_pipeline(),
        );
        device.cmd_bind_descriptor_sets(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            pipeline_resources.pipeline_layout(),
            0,
            slice::from_ref(&self.descriptor_sets[descriptor_index]),
            &[],
        );
        device.cmd_push_constants(
            command_buffer,
            pipeline_resources.pipeline_layout(),
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            push_bytes,
        );
        device.cmd_draw(
            command_buffer,
            PRIVATE_SURFACE_PARTICLE_VERTICES_PER_INSTANCE,
            effective_draw_count.min(u32::MAX as u64).max(1) as u32,
            0,
            0,
        );
    }

    unsafe fn destroy(&mut self, device: &ash::Device) {
        if self.descriptor_pool != vk::DescriptorPool::null() {
            device.destroy_descriptor_pool(self.descriptor_pool, None);
            self.descriptor_pool = vk::DescriptorPool::null();
        }
        self.descriptor_sets =
            [vk::DescriptorSet::null(); PRIVATE_SURFACE_PARTICLE_DESCRIPTOR_SET_COUNT];
        self.diagnostic_buffer_b.destroy(device);
        self.diagnostic_buffer_a.destroy(device);
        self.driver_bank_buffer.destroy(device);
        self.phase_buffer_b.destroy(device);
        self.phase_buffer_a.destroy(device);
        self.particle_output_buffer.destroy(device);
    }
}

unsafe fn destroy_private_descriptor_buffers(
    device: &ash::Device,
    buffers: &mut [SurfaceParticlePrivateBuffer],
) {
    for buffer in buffers {
        buffer.destroy(device);
    }
}

unsafe fn render_sdk_quad_vulkan_probe(
    window: *mut vk::ANativeWindow,
    requested_width: u32,
    requested_height: u32,
    max_frames: u32,
) -> Result<SdkQuadVulkanProbeStats, String> {
    let entry = ash::Entry::load().map_err(|error| format!("vulkan-loader-{error}"))?;
    let app_name = CString::new("rusty-quest-spatial-camera-panel").expect("static app name");
    let engine_name = CString::new("sdk-quad-vulkan-probe").expect("static engine name");
    let app_info = vk::ApplicationInfo::default()
        .application_name(&app_name)
        .application_version(1)
        .engine_name(&engine_name)
        .engine_version(1)
        .api_version(vk::make_api_version(0, 1, 1, 0));
    let instance_extensions = [
        ash::khr::surface::NAME.as_ptr(),
        ash::khr::android_surface::NAME.as_ptr(),
    ];
    let instance_info = vk::InstanceCreateInfo::default()
        .application_info(&app_info)
        .enabled_extension_names(&instance_extensions);
    let instance = entry
        .create_instance(&instance_info, None)
        .map_err(|error| format!("create-instance-{error:?}"))?;

    let surface_loader = ash::khr::surface::Instance::new(&entry, &instance);
    let android_surface_loader = ash::khr::android_surface::Instance::new(&entry, &instance);
    let surface_info = vk::AndroidSurfaceCreateInfoKHR::default().window(window);
    let surface = android_surface_loader
        .create_android_surface(&surface_info, None)
        .map_err(|error| {
            instance.destroy_instance(None);
            format!("create-android-surface-{error:?}")
        })?;

    let physical_devices = instance.enumerate_physical_devices().map_err(|error| {
        surface_loader.destroy_surface(surface, None);
        instance.destroy_instance(None);
        format!("enumerate-physical-devices-{error:?}")
    })?;
    let (physical_device, queue_family_index) =
        select_surface_device(&instance, &surface_loader, surface, &physical_devices).ok_or_else(
            || {
                surface_loader.destroy_surface(surface, None);
                instance.destroy_instance(None);
                "no-graphics-present-queue".to_string()
            },
        )?;

    let queue_priorities = [1.0_f32];
    let queue_info = [vk::DeviceQueueCreateInfo::default()
        .queue_family_index(queue_family_index)
        .queue_priorities(&queue_priorities)];
    let device_extensions = [ash::khr::swapchain::NAME.as_ptr()];
    let device_info = vk::DeviceCreateInfo::default()
        .queue_create_infos(&queue_info)
        .enabled_extension_names(&device_extensions);
    let device = instance
        .create_device(physical_device, &device_info, None)
        .map_err(|error| {
            surface_loader.destroy_surface(surface, None);
            instance.destroy_instance(None);
            format!("create-device-{error:?}")
        })?;
    let queue = device.get_device_queue(queue_family_index, 0);
    let swapchain_loader = ash::khr::swapchain::Device::new(&instance, &device);

    let surface_format = choose_surface_format(
        &surface_loader
            .get_physical_device_surface_formats(physical_device, surface)
            .map_err(|error| {
                device.destroy_device(None);
                surface_loader.destroy_surface(surface, None);
                instance.destroy_instance(None);
                format!("surface-formats-{error:?}")
            })?,
    );
    let capabilities = surface_loader
        .get_physical_device_surface_capabilities(physical_device, surface)
        .map_err(|error| {
            device.destroy_device(None);
            surface_loader.destroy_surface(surface, None);
            instance.destroy_instance(None);
            format!("surface-capabilities-{error:?}")
        })?;
    let present_modes = surface_loader
        .get_physical_device_surface_present_modes(physical_device, surface)
        .unwrap_or_default();
    let present_mode = choose_present_mode(&present_modes);
    let extent = choose_extent(&capabilities, requested_width, requested_height);
    let image_count = choose_image_count(&capabilities);
    let composite_alpha = choose_composite_alpha(capabilities.supported_composite_alpha);
    let swapchain_info = vk::SwapchainCreateInfoKHR::default()
        .surface(surface)
        .min_image_count(image_count)
        .image_format(surface_format.format)
        .image_color_space(surface_format.color_space)
        .image_extent(extent)
        .image_array_layers(1)
        .image_usage(vk::ImageUsageFlags::COLOR_ATTACHMENT)
        .image_sharing_mode(vk::SharingMode::EXCLUSIVE)
        .pre_transform(capabilities.current_transform)
        .composite_alpha(composite_alpha)
        .present_mode(present_mode)
        .clipped(true);
    let swapchain = swapchain_loader
        .create_swapchain(&swapchain_info, None)
        .map_err(|error| {
            device.destroy_device(None);
            surface_loader.destroy_surface(surface, None);
            instance.destroy_instance(None);
            format!("create-swapchain-{error:?}")
        })?;
    let images = swapchain_loader
        .get_swapchain_images(swapchain)
        .map_err(|error| {
            swapchain_loader.destroy_swapchain(swapchain, None);
            device.destroy_device(None);
            surface_loader.destroy_surface(surface, None);
            instance.destroy_instance(None);
            format!("swapchain-images-{error:?}")
        })?;
    let image_views = create_image_views(&device, surface_format.format, &images)?;
    let render_pass = create_render_pass(&device, surface_format.format)?;
    let framebuffers = create_framebuffers(&device, render_pass, extent, &image_views)?;
    let command_pool_info = vk::CommandPoolCreateInfo::default()
        .queue_family_index(queue_family_index)
        .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER);
    let command_pool = device
        .create_command_pool(&command_pool_info, None)
        .map_err(|error| format!("create-command-pool-{error:?}"))?;
    let command_allocate_info = vk::CommandBufferAllocateInfo::default()
        .command_pool(command_pool)
        .level(vk::CommandBufferLevel::PRIMARY)
        .command_buffer_count(images.len() as u32);
    let command_buffers = device
        .allocate_command_buffers(&command_allocate_info)
        .map_err(|error| format!("allocate-command-buffers-{error:?}"))?;
    let semaphore_info = vk::SemaphoreCreateInfo::default();
    let image_available = device
        .create_semaphore(&semaphore_info, None)
        .map_err(|error| format!("create-image-semaphore-{error:?}"))?;
    let render_finished = device
        .create_semaphore(&semaphore_info, None)
        .map_err(|error| format!("create-render-semaphore-{error:?}"))?;
    let fence_info = vk::FenceCreateInfo::default().flags(vk::FenceCreateFlags::SIGNALED);
    let frame_fence = device
        .create_fence(&fence_info, None)
        .map_err(|error| format!("create-frame-fence-{error:?}"))?;

    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=sdk-owned-quad-vulkan-probe status=render-loop-ready nativeVulkanProducer=true renderPolicy=sdk-owned-scenequadlayer-android-surface-wsi producerPath=ANativeWindow-VkSurfaceKHR-Vulkan-WSI swapchainImages={} extent={}x{} surfaceFormat={:?} presentMode={:?} compositeAlpha={:?} renderPattern=animated-clear-color trianglePattern=false computeShader=false particleShader=false privateShaderStack=false runtimeCrash=false",
            images.len(),
            extent.width,
            extent.height,
            surface_format.format,
            present_mode,
            composite_alpha,
        ),
    );

    let mut frames_presented = 0_u32;
    loop {
        if STOP_SDK_QUAD_VULKAN_PROBE.load(Ordering::Relaxed) {
            break;
        }
        if frames_presented >= max_frames {
            break;
        }
        device
            .wait_for_fences(&[frame_fence], true, u64::MAX)
            .map_err(|error| format!("wait-fence-{error:?}"))?;
        device
            .reset_fences(&[frame_fence])
            .map_err(|error| format!("reset-fence-{error:?}"))?;
        let image_index = match swapchain_loader.acquire_next_image(
            swapchain,
            u64::MAX,
            image_available,
            vk::Fence::null(),
        ) {
            Ok((image_index, _suboptimal)) => image_index,
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => break,
            Err(error) => return Err(format!("acquire-next-image-{error:?}")),
        };
        let command_buffer = command_buffers[image_index as usize];
        record_sdk_quad_vulkan_probe_command_buffer(
            &device,
            command_buffer,
            render_pass,
            framebuffers[image_index as usize],
            extent,
            frames_presented,
        )?;
        let wait_semaphores = [image_available];
        let wait_stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
        let signal_semaphores = [render_finished];
        let submit_command_buffers = [command_buffer];
        let submit_info = [vk::SubmitInfo::default()
            .wait_semaphores(&wait_semaphores)
            .wait_dst_stage_mask(&wait_stages)
            .command_buffers(&submit_command_buffers)
            .signal_semaphores(&signal_semaphores)];
        device
            .queue_submit(queue, &submit_info, frame_fence)
            .map_err(|error| format!("queue-submit-{error:?}"))?;
        let swapchains = [swapchain];
        let image_indices = [image_index];
        let present_info = vk::PresentInfoKHR::default()
            .wait_semaphores(&signal_semaphores)
            .swapchains(&swapchains)
            .image_indices(&image_indices);
        match swapchain_loader.queue_present(queue, &present_info) {
            Ok(_suboptimal) => {}
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => break,
            Err(error) => return Err(format!("queue-present-{error:?}")),
        }
        frames_presented = frames_presented.saturating_add(1);
        if frames_presented == 1 {
            android_log_info(
                "RQSpatialCameraPanelNative",
                &format!(
                    "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=sdk-owned-quad-vulkan-probe status=first-frame-presented nativeVulkanProducer=true renderPolicy=sdk-owned-scenequadlayer-android-surface-wsi framesPresented={} extent={}x{} swapchainImages={} presentResult=success renderPattern=animated-clear-color runtimeCrash=false",
                    frames_presented,
                    extent.width,
                    extent.height,
                    images.len(),
                ),
            );
        }
    }

    device
        .device_wait_idle()
        .map_err(|error| format!("device-wait-idle-{error:?}"))?;
    device.destroy_fence(frame_fence, None);
    device.destroy_semaphore(render_finished, None);
    device.destroy_semaphore(image_available, None);
    device.destroy_command_pool(command_pool, None);
    for framebuffer in framebuffers {
        device.destroy_framebuffer(framebuffer, None);
    }
    device.destroy_render_pass(render_pass, None);
    for image_view in image_views {
        device.destroy_image_view(image_view, None);
    }
    swapchain_loader.destroy_swapchain(swapchain, None);
    device.destroy_device(None);
    surface_loader.destroy_surface(surface, None);
    instance.destroy_instance(None);

    Ok(SdkQuadVulkanProbeStats {
        frames_presented,
        extent,
        swapchain_image_count: images.len(),
    })
}

unsafe fn record_sdk_quad_vulkan_probe_command_buffer(
    device: &ash::Device,
    command_buffer: vk::CommandBuffer,
    render_pass: vk::RenderPass,
    framebuffer: vk::Framebuffer,
    extent: vk::Extent2D,
    frame_index: u32,
) -> Result<(), String> {
    device
        .reset_command_buffer(command_buffer, vk::CommandBufferResetFlags::empty())
        .map_err(|error| format!("reset-command-buffer-{error:?}"))?;
    let begin_info = vk::CommandBufferBeginInfo::default();
    device
        .begin_command_buffer(command_buffer, &begin_info)
        .map_err(|error| format!("begin-command-buffer-{error:?}"))?;
    let phase = (frame_index % 180) as f32 / 180.0;
    let clear_values = [vk::ClearValue {
        color: vk::ClearColorValue {
            float32: [phase, 1.0 - phase, 0.25 + 0.5 * phase, 1.0],
        },
    }];
    let render_area = vk::Rect2D {
        offset: vk::Offset2D { x: 0, y: 0 },
        extent,
    };
    let render_pass_info = vk::RenderPassBeginInfo::default()
        .render_pass(render_pass)
        .framebuffer(framebuffer)
        .render_area(render_area)
        .clear_values(&clear_values);
    device.cmd_begin_render_pass(
        command_buffer,
        &render_pass_info,
        vk::SubpassContents::INLINE,
    );
    device.cmd_end_render_pass(command_buffer);
    device
        .end_command_buffer(command_buffer)
        .map_err(|error| format!("end-command-buffer-{error:?}"))?;
    Ok(())
}

unsafe fn render_surface_particles(
    window: *mut vk::ANativeWindow,
    requested_width: u32,
    requested_height: u32,
    particle_count: u32,
    max_frames: u32,
    openxr_handles: LiveHandOpenXrHandles,
) -> Result<SurfaceParticleStats, String> {
    let entry = ash::Entry::load().map_err(|error| format!("vulkan-loader-{error}"))?;
    let app_name = CString::new("rusty-quest-spatial-camera-panel").expect("static app name");
    let engine_name = CString::new("surface-particle-layer").expect("static engine name");
    let app_info = vk::ApplicationInfo::default()
        .application_name(&app_name)
        .application_version(1)
        .engine_name(&engine_name)
        .engine_version(1)
        .api_version(vk::make_api_version(0, 1, 1, 0));
    let instance_extensions = [
        ash::khr::surface::NAME.as_ptr(),
        ash::khr::android_surface::NAME.as_ptr(),
    ];
    let instance_info = vk::InstanceCreateInfo::default()
        .application_info(&app_info)
        .enabled_extension_names(&instance_extensions);
    let instance = entry
        .create_instance(&instance_info, None)
        .map_err(|error| format!("create-instance-{error:?}"))?;

    let surface_loader = ash::khr::surface::Instance::new(&entry, &instance);
    let android_surface_loader = ash::khr::android_surface::Instance::new(&entry, &instance);
    let surface_info = vk::AndroidSurfaceCreateInfoKHR::default().window(window);
    let surface = android_surface_loader
        .create_android_surface(&surface_info, None)
        .map_err(|error| {
            instance.destroy_instance(None);
            format!("create-android-surface-{error:?}")
        })?;

    let physical_devices = instance.enumerate_physical_devices().map_err(|error| {
        surface_loader.destroy_surface(surface, None);
        instance.destroy_instance(None);
        format!("enumerate-physical-devices-{error:?}")
    })?;
    let (physical_device, queue_family_index) =
        select_surface_device(&instance, &surface_loader, surface, &physical_devices).ok_or_else(
            || {
                surface_loader.destroy_surface(surface, None);
                instance.destroy_instance(None);
                "no-graphics-present-queue".to_string()
            },
        )?;

    let queue_priorities = [1.0_f32];
    let queue_info = [vk::DeviceQueueCreateInfo::default()
        .queue_family_index(queue_family_index)
        .queue_priorities(&queue_priorities)];
    let device_extensions = [ash::khr::swapchain::NAME.as_ptr()];
    let device_info = vk::DeviceCreateInfo::default()
        .queue_create_infos(&queue_info)
        .enabled_extension_names(&device_extensions);
    let device = instance
        .create_device(physical_device, &device_info, None)
        .map_err(|error| {
            surface_loader.destroy_surface(surface, None);
            instance.destroy_instance(None);
            format!("create-device-{error:?}")
        })?;
    let queue = device.get_device_queue(queue_family_index, 0);
    let mut swapchain_carrier = match SurfaceParticleSwapchainCarrier::create(
        &instance,
        &device,
        &surface_loader,
        surface,
        physical_device,
        requested_width,
        requested_height,
    ) {
        Ok(carrier) => carrier,
        Err(error) => {
            device.destroy_device(None);
            surface_loader.destroy_surface(surface, None);
            instance.destroy_instance(None);
            return Err(error);
        }
    };
    let mut frame_targets = SurfaceParticleFrameTargets::create(
        &device,
        swapchain_carrier.format(),
        swapchain_carrier.extent(),
        swapchain_carrier.images(),
    )?;
    let mut pipeline_resources =
        SurfaceParticlePipelineResources::create(&device, frame_targets.render_pass)?;
    let memory_properties = instance.get_physical_device_memory_properties(physical_device);
    let mut renderer = SurfaceParticleRenderer::new_from_build_metadata(
        &device,
        &memory_properties,
        frame_targets.render_pass,
        openxr_handles,
    )?;
    let mut openxr_world_mapper = SurfaceParticleOpenXrViewMapper::new(openxr_handles);
    let particle_buffer_size =
        particle_count as vk::DeviceSize * mem::size_of::<SurfaceParticleRow>() as vk::DeviceSize;
    let (particle_buffer, particle_buffer_memory) = create_buffer_with_memory(
        &instance,
        &device,
        physical_device,
        particle_buffer_size,
        vk::BufferUsageFlags::STORAGE_BUFFER,
        vk::MemoryPropertyFlags::DEVICE_LOCAL,
    )?;
    let mut descriptor_resources = SurfaceParticleDescriptorResources::create(
        &device,
        pipeline_resources.descriptor_set_layout(),
        particle_buffer,
        particle_buffer_size,
    )?;
    let mut frame_loop = SurfaceParticleFrameLoopResources::create(
        &device,
        queue_family_index,
        swapchain_carrier.image_count(),
    )?;
    let private_build_config = SurfaceParticlePrivateBuildConfig::current();
    let renderer_mode = selected_surface_particle_renderer_mode(private_build_config);
    let private_main_draw_enabled =
        renderer_mode == SurfaceParticleRendererMode::PrivateMainDrawOnly;
    let mut private_payload_gpu_resources = if private_main_draw_enabled {
        SurfaceParticlePrivatePayloadGpuResources::create_if_staged(
            &instance,
            &device,
            physical_device,
            queue,
            frame_loop.command_pool,
            private_build_config,
        )?
    } else {
        None
    };
    let private_payload_gpu_marker_fields = private_payload_gpu_resources
        .as_ref()
        .map(SurfaceParticlePrivatePayloadGpuResources::marker_fields)
        .unwrap_or_else(|| {
            "privateSurfaceParticleStagedGpuBuffersResident=false privateSurfaceParticlePayloadGpuUploadBytes=0 privateSurfaceParticlePayloadBufferMode=not-staged privateSurfaceParticlePayloadBoundToPrivateRenderer=false".to_string()
        });
    let mut private_pipeline_resources = if private_main_draw_enabled {
        SurfaceParticlePrivatePipelineResources::create_if_staged(
            &device,
            frame_targets.render_pass,
            private_build_config,
        )?
    } else {
        None
    };
    let private_pipeline_marker_fields = private_pipeline_resources
        .as_ref()
        .map(SurfaceParticlePrivatePipelineResources::marker_fields)
        .unwrap_or(
            "privateSurfaceParticlePrivateComputePipelineReady=false privateSurfaceParticlePrivateGraphicsPipelineReady=false privateSurfaceParticlePrivateDescriptorBindingPlan=not-staged privateSurfaceParticlePrivatePushBytes=0 privateSurfaceParticlePrivateDispatchEnabled=false privateSurfaceParticlePrivateDrawEnabled=false privateSurfaceParticlePrivateTracerDispatchEnabled=false privateSurfaceParticlePrivateTracerDrawEnabled=false",
        );
    let mut private_descriptor_resources = if private_main_draw_enabled {
        SurfaceParticlePrivateDescriptorResources::create_if_ready(
            &instance,
            &device,
            physical_device,
            private_build_config,
            private_payload_gpu_resources.as_ref(),
            private_pipeline_resources.as_ref(),
        )?
    } else {
        None
    };
    let private_descriptor_marker_fields = private_descriptor_resources
        .as_ref()
        .map(SurfaceParticlePrivateDescriptorResources::marker_fields)
        .unwrap_or_else(|| {
            "privateSurfaceParticlePrivateDescriptorSetsReady=false privateSurfaceParticlePrivateDescriptorSetCount=0 privateSurfaceParticlePrivateMainOnlyBuffersReady=false privateSurfaceParticlePrivateMainOnly=false privateSurfaceParticlePrivateTracerBuffersReady=false privateSurfaceParticlePrivateParticleCount=0 privateSurfaceParticlePrivateTracerStateCapacity=0 privateSurfaceParticlePrivateTracerDrawCount=0 privateSurfaceParticlePrivateRetentionIndicatorDrawCount=0 privateSurfaceParticlePrivateDrawCount=0 privateSurfaceParticlePrivateParticleOutputRows=0 privateSurfaceParticlePrivatePhaseStateRows=0 privateSurfaceParticlePrivateDriverBankRows=0 privateSurfaceParticlePrivateDiagnosticWords=0 privateSurfaceParticlePrivateDescriptorBufferBytes=0 privateSurfaceParticlePrivateDescriptorsBoundToDispatch=false".to_string()
        });
    let private_compute_ready_marker_fields = private_descriptor_resources
        .as_ref()
        .map(SurfaceParticlePrivateDescriptorResources::compute_ready_marker_fields)
        .unwrap_or_else(|| {
            "privateSurfaceParticleMainComputeDispatchReady=false privateSurfaceParticleMainComputeDispatchRecorded=false privateSurfaceParticleMainComputeDispatchWorkgroups=0 privateSurfaceParticleMainComputeDispatchParticleCount=0 privateSurfaceParticleMainComputeDispatchDrawCount=0 privateSurfaceParticleMainComputeDispatchTracerMaxCount=0 privateSurfaceParticleMainComputeDispatchTracerDrawCount=0 privateSurfaceParticleMainComputeDispatchRetentionIndicatorDrawCount=0 privateSurfaceParticleMainComputeDispatchVisible=false".to_string()
        });
    let private_compute_recorded_marker_fields = private_descriptor_resources
        .as_ref()
        .map(SurfaceParticlePrivateDescriptorResources::compute_recorded_marker_fields)
        .unwrap_or_else(|| {
            "privateSurfaceParticleMainComputeDispatchReady=false privateSurfaceParticleMainComputeDispatchRecorded=false privateSurfaceParticleMainComputeDispatchWorkgroups=0 privateSurfaceParticleMainComputeDispatchParticleCount=0 privateSurfaceParticleMainComputeDispatchDrawCount=0 privateSurfaceParticleMainComputeDispatchTracerMaxCount=0 privateSurfaceParticleMainComputeDispatchTracerDrawCount=0 privateSurfaceParticleMainComputeDispatchRetentionIndicatorDrawCount=0 privateSurfaceParticleMainComputeDispatchVisible=false".to_string()
        });
    let private_draw_ready_marker_fields = private_descriptor_resources
        .as_ref()
        .map(SurfaceParticlePrivateDescriptorResources::draw_ready_marker_fields)
        .unwrap_or_else(|| {
            "privateSurfaceParticleMainDrawReady=false privateSurfaceParticleMainDrawRecorded=false privateSurfaceParticleMainDrawVerticesPerInstance=0 privateSurfaceParticleMainDrawInstanceCount=0 privateSurfaceParticleMainDrawParticleCount=0 privateSurfaceParticleMainDrawTracerDrawCount=0 privateSurfaceParticleMainDrawRetentionIndicatorDrawCount=0 privateSurfaceParticleMainDrawVisible=false privateSurfaceParticleMainDrawProjection=not-staged".to_string()
        });
    let private_draw_recorded_marker_fields = private_descriptor_resources
        .as_ref()
        .map(SurfaceParticlePrivateDescriptorResources::draw_recorded_marker_fields)
        .unwrap_or_else(|| {
            "privateSurfaceParticleMainDrawReady=false privateSurfaceParticleMainDrawRecorded=false privateSurfaceParticleMainDrawVerticesPerInstance=0 privateSurfaceParticleMainDrawInstanceCount=0 privateSurfaceParticleMainDrawParticleCount=0 privateSurfaceParticleMainDrawTracerDrawCount=0 privateSurfaceParticleMainDrawRetentionIndicatorDrawCount=0 privateSurfaceParticleMainDrawVisible=false privateSurfaceParticleMainDrawProjection=not-staged".to_string()
        });
    let start = Instant::now();
    let mut frames_presented = 0_u32;
    let stereo_layout = surface_particle_stereo_layout(frame_targets.extent);
    let initial_params = current_surface_particle_parameters();
    let private_main_draw_only =
        renderer.mode() == SurfaceParticleRendererMode::PrivateMainDrawOnly;
    let private_visual_ready_marker_fields = private_descriptor_resources
        .as_ref()
        .map(SurfaceParticlePrivateDescriptorResources::compact_ready_marker_fields)
        .unwrap_or_else(|| {
            "privateSurfaceParticlePrivateDescriptorSetsReady=false privateSurfaceParticlePrivateTracerBuffersReady=false privateSurfaceParticlePrivateParticleCount=0 privateSurfaceParticlePrivateTracerStateCapacity=0 privateSurfaceParticlePrivateTracerDrawCount=0 privateSurfaceParticlePrivateDrawCount=0 privateSurfaceParticleMainComputeDispatchReady=false privateSurfaceParticleMainComputeDispatchRecorded=false privateSurfaceParticleMainComputeDispatchWorkgroups=0 privateSurfaceParticleMainComputeDispatchParticleCount=0 privateSurfaceParticleMainComputeDispatchTracerDrawCount=0 privateSurfaceParticleMainDrawReady=false privateSurfaceParticleMainDrawRecorded=false privateSurfaceParticleMainDrawParticleCount=0 privateSurfaceParticleMainDrawTracerDrawCount=0 privateSurfaceParticleMainDrawVisible=false".to_string()
        });
    let private_visual_ready_counts_marker_fields = private_descriptor_resources
        .as_ref()
        .map(SurfaceParticlePrivateDescriptorResources::compact_counts_ready_marker_fields)
        .unwrap_or_else(|| {
            "privateSurfaceParticleMainComputeDispatchRecorded=false privateSurfaceParticleMainDrawRecorded=false privateSurfaceParticleMainDrawParticleCount=0 privateSurfaceParticleMainDrawTracerDrawCount=0 privateSurfaceParticlePrivateParticleCount=0 privateSurfaceParticlePrivateTracerStateCapacity=0 privateSurfaceParticlePrivateTracerDrawCount=0 privateSurfaceParticlePrivateDrawCount=0 privateSurfaceParticlePrivateDescriptorSetsReady=false privateSurfaceParticlePrivateTracerBuffersReady=false".to_string()
        });
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=private-main-tracer-ready-counts-compact renderPolicy=native-vulkan-wsi-surface-panel privateSurfaceParticleProfileIdHash={:016x} privateSurfaceParticleRendererMode={} {}",
            private_build_config.profile_id_hash,
            match renderer.mode() {
                SurfaceParticleRendererMode::PublicHandAnchorProof => "placeholder-unavailable",
                SurfaceParticleRendererMode::PrivateMetadataOnly => "metadata-only",
                SurfaceParticleRendererMode::PrivateMainDrawOnly => "main-draw-only",
            },
            private_visual_ready_counts_marker_fields,
        ),
    );
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=private-main-tracer-ready-compact renderPolicy=native-vulkan-wsi-surface-panel {} {} {}",
            renderer.mode().compact_marker_fields(),
            private_build_config.visual_compact_marker_fields(),
            private_visual_ready_marker_fields,
        ),
    );
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=render-loop-ready renderPolicy=native-vulkan-wsi-surface-panel native-surface-compute-stereo-proof=true sideBySideStereoProof=true stereoDebugMarkers=false computeParticleStateBuffer=true computeShaderDispatchReady=true {} {} liveHandDepthOffsetParameterSource=runtime-hotload-android-property liveHandDepthOffsetProperty={} liveHandDepthOffsetMeters={:.3} particleDiagnosticModeProperty={} particleDiagnosticMode={} particleDiagnosticModeName={} particleStorageBufferBytes={} particleCount={} extent={}x{} stereoMode={} perEyeExtent={}x{} packedExtent={}x{} {} {} {} {} {} {} {} swapchainImages={}",
            renderer.mode().marker_fields(),
            private_build_config.marker_fields(),
            SURFACE_PARTICLE_LIVE_HAND_DEPTH_OFFSET_PROPERTY,
            initial_params.live_hand_depth_offset_meters,
            SURFACE_PARTICLE_DIAGNOSTIC_MODE_PROPERTY,
            initial_params.diagnostic_mode,
            surface_particle_diagnostic_mode_name(initial_params.diagnostic_mode),
            particle_buffer_size,
            particle_count,
            frame_targets.extent.width,
            frame_targets.extent.height,
            stereo_layout.stereo_mode,
            stereo_layout.per_eye_extent.width,
            stereo_layout.per_eye_extent.height,
            stereo_layout.packed_extent.width,
            stereo_layout.packed_extent.height,
            SURFACE_PARTICLE_PROJECTION_MARKERS,
            renderer.marker_fields(),
            private_payload_gpu_marker_fields,
            private_pipeline_marker_fields,
            private_descriptor_marker_fields,
            private_compute_ready_marker_fields,
            private_draw_ready_marker_fields,
            swapchain_carrier.image_count(),
        ),
    );
    loop {
        if STOP_SURFACE_PARTICLES.load(Ordering::Relaxed) {
            break;
        }
        if max_frames > 0 && frames_presented >= max_frames {
            break;
        }
        frame_loop.wait_and_reset_fence(&device)?;
        let image_index =
            match swapchain_carrier.acquire_next_image(frame_loop.image_available())? {
                Some(image_index) => image_index,
                None => break,
            };
        let command_buffer = frame_loop.command_buffer(image_index);
        renderer.update_frame_resources(&device)?;
        update_surface_particle_openxr_world_anchor(
            &mut openxr_world_mapper,
            current_surface_particle_panel_projection(),
        );
        record_command_buffer(
            &device,
            command_buffer,
            frame_targets.render_pass,
            frame_targets.framebuffer(image_index),
            frame_targets.extent,
            pipeline_resources.graphics_pipeline(),
            pipeline_resources.compute_pipeline(),
            pipeline_resources.pipeline_layout(),
            descriptor_resources.descriptor_set(),
            particle_count,
            frames_presented,
            start.elapsed().as_secs_f32(),
            &mut renderer,
            private_pipeline_resources.as_ref(),
            private_descriptor_resources.as_mut(),
        )?;
        frame_loop.submit(&device, queue, command_buffer)?;
        if !swapchain_carrier.present(queue, image_index, frame_loop.render_finished())? {
            break;
        }
        frames_presented = frames_presented.saturating_add(1);
        if frames_presented % 90 == 0 {
            let elapsed_ms = start.elapsed().as_secs_f64() * 1000.0;
            let average_fps = if elapsed_ms > 0.0 {
                (frames_presented as f64 * 1000.0) / elapsed_ms
            } else {
                0.0
            };
            android_log_info(
                "RQSpatialCameraPanelNative",
                &format!(
                    "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=frame-pacing-sample renderPolicy=native-vulkan-wsi-surface-panel framesPresented={} elapsedMs={:.1} averageFps={:.2} privateSurfaceParticlePublicProofComputeSkipped={} {}",
                    frames_presented,
                    elapsed_ms,
                    average_fps,
                    bool_token(private_main_draw_only),
                    renderer.mode().compact_marker_fields(),
                ),
            );
        }
        if frames_presented == 1 {
            let private_visual_presented_marker_fields = private_descriptor_resources
                .as_ref()
                .map(SurfaceParticlePrivateDescriptorResources::compact_recorded_marker_fields)
                .unwrap_or_else(|| {
                    "privateSurfaceParticlePrivateDescriptorSetsReady=false privateSurfaceParticlePrivateTracerBuffersReady=false privateSurfaceParticlePrivateParticleCount=0 privateSurfaceParticlePrivateTracerStateCapacity=0 privateSurfaceParticlePrivateTracerDrawCount=0 privateSurfaceParticlePrivateDrawCount=0 privateSurfaceParticleMainComputeDispatchReady=false privateSurfaceParticleMainComputeDispatchRecorded=false privateSurfaceParticleMainComputeDispatchWorkgroups=0 privateSurfaceParticleMainComputeDispatchParticleCount=0 privateSurfaceParticleMainComputeDispatchTracerDrawCount=0 privateSurfaceParticleMainDrawReady=false privateSurfaceParticleMainDrawRecorded=false privateSurfaceParticleMainDrawParticleCount=0 privateSurfaceParticleMainDrawTracerDrawCount=0 privateSurfaceParticleMainDrawVisible=false".to_string()
                });
            let private_visual_presented_counts_marker_fields = private_descriptor_resources
                .as_ref()
                .map(SurfaceParticlePrivateDescriptorResources::compact_counts_recorded_marker_fields)
                .unwrap_or_else(|| {
                    "privateSurfaceParticleMainComputeDispatchRecorded=false privateSurfaceParticleMainDrawRecorded=false privateSurfaceParticleMainDrawParticleCount=0 privateSurfaceParticleMainDrawTracerDrawCount=0 privateSurfaceParticlePrivateParticleCount=0 privateSurfaceParticlePrivateTracerStateCapacity=0 privateSurfaceParticlePrivateTracerDrawCount=0 privateSurfaceParticlePrivateDrawCount=0 privateSurfaceParticlePrivateDescriptorSetsReady=false privateSurfaceParticlePrivateTracerBuffersReady=false".to_string()
                });
            android_log_info(
                "RQSpatialCameraPanelNative",
                &format!(
                    "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=private-main-tracer-presented-counts-compact renderPolicy=native-vulkan-wsi-surface-panel framesPresented={} privateSurfaceParticleProfileIdHash={:016x} privateSurfaceParticleRendererMode={} {}",
                    frames_presented,
                    private_build_config.profile_id_hash,
                    match renderer.mode() {
                        SurfaceParticleRendererMode::PublicHandAnchorProof => "placeholder-unavailable",
                        SurfaceParticleRendererMode::PrivateMetadataOnly => "metadata-only",
                        SurfaceParticleRendererMode::PrivateMainDrawOnly => "main-draw-only",
                    },
                    private_visual_presented_counts_marker_fields,
                ),
            );
            android_log_info(
                "RQSpatialCameraPanelNative",
                &format!(
                    "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=private-main-tracer-presented-compact renderPolicy=native-vulkan-wsi-surface-panel framesPresented={} {} {} {}",
                    frames_presented,
                    renderer.mode().compact_marker_fields(),
                    private_build_config.visual_compact_marker_fields(),
                    private_visual_presented_marker_fields,
                ),
            );
            android_log_info(
                    "RQSpatialCameraPanelNative",
                    &format!(
                    "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-surface-particle-layer status=first-frame-presented renderPolicy=native-vulkan-wsi-surface-panel native-surface-compute-stereo-proof=true sideBySideStereoProof=true stereoDebugMarkers=false computeParticleStateBuffer=true {} {} particleCount={} extent={}x{} stereoMode={} perEyeExtent={}x{} packedExtent={}x{} {} {} {} {} {} {} {}",
                    renderer.mode().marker_fields(),
                    private_build_config.marker_fields(),
                    particle_count,
                    frame_targets.extent.width,
                    frame_targets.extent.height,
                    stereo_layout.stereo_mode,
                    stereo_layout.per_eye_extent.width,
                    stereo_layout.per_eye_extent.height,
                    stereo_layout.packed_extent.width,
                    stereo_layout.packed_extent.height,
                    SURFACE_PARTICLE_PROJECTION_MARKERS,
                    renderer.marker_fields(),
                    private_payload_gpu_marker_fields,
                    private_pipeline_marker_fields,
                    private_descriptor_marker_fields,
                    private_compute_recorded_marker_fields,
                    private_draw_recorded_marker_fields,
                ),
            );
        }
    }

    device
        .device_wait_idle()
        .map_err(|error| format!("device-wait-idle-{error:?}"))?;
    let renderer_mode = renderer.mode();
    renderer.destroy(&device);
    openxr_world_mapper.destroy();
    frame_loop.destroy(&device);
    if let Some(resources) = private_descriptor_resources.as_mut() {
        resources.destroy(&device);
    }
    if let Some(resources) = private_payload_gpu_resources.as_mut() {
        resources.destroy(&device);
    }
    if let Some(resources) = private_pipeline_resources.as_mut() {
        resources.destroy(&device);
    }
    descriptor_resources.destroy(&device);
    device.destroy_buffer(particle_buffer, None);
    device.free_memory(particle_buffer_memory, None);
    pipeline_resources.destroy(&device);
    frame_targets.destroy(&device);
    swapchain_carrier.destroy();
    device.destroy_device(None);
    surface_loader.destroy_surface(surface, None);
    instance.destroy_instance(None);

    Ok(SurfaceParticleStats {
        frames_presented,
        particle_count,
        extent: frame_targets.extent,
        renderer_mode,
    })
}

unsafe fn select_surface_device(
    instance: &ash::Instance,
    surface_loader: &ash::khr::surface::Instance,
    surface: vk::SurfaceKHR,
    physical_devices: &[vk::PhysicalDevice],
) -> Option<(vk::PhysicalDevice, u32)> {
    for physical_device in physical_devices {
        let queue_families = instance.get_physical_device_queue_family_properties(*physical_device);
        for (index, family) in queue_families.iter().enumerate() {
            if !family
                .queue_flags
                .contains(vk::QueueFlags::GRAPHICS | vk::QueueFlags::COMPUTE)
            {
                continue;
            }
            let present_supported = surface_loader
                .get_physical_device_surface_support(*physical_device, index as u32, surface)
                .unwrap_or(false);
            if present_supported {
                return Some((*physical_device, index as u32));
            }
        }
    }
    None
}

fn choose_surface_format(formats: &[vk::SurfaceFormatKHR]) -> vk::SurfaceFormatKHR {
    formats
        .iter()
        .copied()
        .find(|format| {
            matches!(
                format.format,
                vk::Format::B8G8R8A8_UNORM | vk::Format::R8G8B8A8_UNORM
            )
        })
        .unwrap_or_else(|| {
            formats.first().copied().unwrap_or(vk::SurfaceFormatKHR {
                format: vk::Format::B8G8R8A8_UNORM,
                color_space: vk::ColorSpaceKHR::SRGB_NONLINEAR,
            })
        })
}

fn choose_present_mode(modes: &[vk::PresentModeKHR]) -> vk::PresentModeKHR {
    if modes.contains(&vk::PresentModeKHR::FIFO) {
        vk::PresentModeKHR::FIFO
    } else {
        modes.first().copied().unwrap_or(vk::PresentModeKHR::FIFO)
    }
}

fn choose_extent(
    capabilities: &vk::SurfaceCapabilitiesKHR,
    requested_width: u32,
    requested_height: u32,
) -> vk::Extent2D {
    if capabilities.current_extent.width != u32::MAX {
        return capabilities.current_extent;
    }
    vk::Extent2D {
        width: requested_width.clamp(
            capabilities.min_image_extent.width,
            capabilities
                .max_image_extent
                .width
                .max(capabilities.min_image_extent.width),
        ),
        height: requested_height.clamp(
            capabilities.min_image_extent.height,
            capabilities
                .max_image_extent
                .height
                .max(capabilities.min_image_extent.height),
        ),
    }
}

fn choose_image_count(capabilities: &vk::SurfaceCapabilitiesKHR) -> u32 {
    let requested = capabilities.min_image_count.saturating_add(1).max(2);
    if capabilities.max_image_count > 0 {
        requested.min(capabilities.max_image_count)
    } else {
        requested
    }
}

fn choose_composite_alpha(supported: vk::CompositeAlphaFlagsKHR) -> vk::CompositeAlphaFlagsKHR {
    [
        vk::CompositeAlphaFlagsKHR::OPAQUE,
        vk::CompositeAlphaFlagsKHR::PRE_MULTIPLIED,
        vk::CompositeAlphaFlagsKHR::POST_MULTIPLIED,
        vk::CompositeAlphaFlagsKHR::INHERIT,
    ]
    .into_iter()
    .find(|candidate| supported.contains(*candidate))
    .unwrap_or(vk::CompositeAlphaFlagsKHR::OPAQUE)
}

unsafe fn create_image_views(
    device: &ash::Device,
    format: vk::Format,
    images: &[vk::Image],
) -> Result<Vec<vk::ImageView>, String> {
    let mut views = Vec::with_capacity(images.len());
    for image in images {
        let view_info = vk::ImageViewCreateInfo::default()
            .image(*image)
            .view_type(vk::ImageViewType::TYPE_2D)
            .format(format)
            .components(vk::ComponentMapping::default())
            .subresource_range(
                vk::ImageSubresourceRange::default()
                    .aspect_mask(vk::ImageAspectFlags::COLOR)
                    .level_count(1)
                    .layer_count(1),
            );
        views.push(
            device
                .create_image_view(&view_info, None)
                .map_err(|error| format!("create-image-view-{error:?}"))?,
        );
    }
    Ok(views)
}

unsafe fn create_render_pass(
    device: &ash::Device,
    format: vk::Format,
) -> Result<vk::RenderPass, String> {
    let color_attachment = vk::AttachmentDescription::default()
        .format(format)
        .samples(vk::SampleCountFlags::TYPE_1)
        .load_op(vk::AttachmentLoadOp::CLEAR)
        .store_op(vk::AttachmentStoreOp::STORE)
        .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE)
        .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
        .initial_layout(vk::ImageLayout::UNDEFINED)
        .final_layout(vk::ImageLayout::PRESENT_SRC_KHR);
    let color_attachment_ref = vk::AttachmentReference::default()
        .attachment(0)
        .layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL);
    let subpass = vk::SubpassDescription::default()
        .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
        .color_attachments(slice::from_ref(&color_attachment_ref));
    let dependency = vk::SubpassDependency::default()
        .src_subpass(vk::SUBPASS_EXTERNAL)
        .dst_subpass(0)
        .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
        .dst_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
        .dst_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE);
    let render_pass_info = vk::RenderPassCreateInfo::default()
        .attachments(slice::from_ref(&color_attachment))
        .subpasses(slice::from_ref(&subpass))
        .dependencies(slice::from_ref(&dependency));
    device
        .create_render_pass(&render_pass_info, None)
        .map_err(|error| format!("create-render-pass-{error:?}"))
}

unsafe fn create_framebuffers(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    extent: vk::Extent2D,
    image_views: &[vk::ImageView],
) -> Result<Vec<vk::Framebuffer>, String> {
    let mut framebuffers = Vec::with_capacity(image_views.len());
    for image_view in image_views {
        let attachments = [*image_view];
        let framebuffer_info = vk::FramebufferCreateInfo::default()
            .render_pass(render_pass)
            .attachments(&attachments)
            .width(extent.width)
            .height(extent.height)
            .layers(1);
        framebuffers.push(
            device
                .create_framebuffer(&framebuffer_info, None)
                .map_err(|error| format!("create-framebuffer-{error:?}"))?,
        );
    }
    Ok(framebuffers)
}

unsafe fn create_descriptor_set_layout(
    device: &ash::Device,
) -> Result<vk::DescriptorSetLayout, String> {
    let binding = vk::DescriptorSetLayoutBinding::default()
        .binding(0)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::COMPUTE | vk::ShaderStageFlags::VERTEX);
    let layout_info =
        vk::DescriptorSetLayoutCreateInfo::default().bindings(slice::from_ref(&binding));
    device
        .create_descriptor_set_layout(&layout_info, None)
        .map_err(|error| format!("create-descriptor-set-layout-{error:?}"))
}

unsafe fn create_pipeline_layout(
    device: &ash::Device,
    descriptor_set_layout: vk::DescriptorSetLayout,
) -> Result<vk::PipelineLayout, String> {
    let push_range = vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::COMPUTE | vk::ShaderStageFlags::VERTEX)
        .offset(0)
        .size(mem::size_of::<SurfaceParticlePush>() as u32);
    let set_layouts = [descriptor_set_layout];
    let layout_info = vk::PipelineLayoutCreateInfo::default()
        .set_layouts(&set_layouts)
        .push_constant_ranges(slice::from_ref(&push_range));
    device
        .create_pipeline_layout(&layout_info, None)
        .map_err(|error| format!("create-pipeline-layout-{error:?}"))
}

unsafe fn create_graphics_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let vert_module = create_shader_module(
        device,
        include_bytes!(concat!(env!("OUT_DIR"), "/surface_particles.vert.spv")),
    )?;
    let frag_module = create_shader_module(
        device,
        include_bytes!(concat!(env!("OUT_DIR"), "/surface_particles.frag.spv")),
    )?;
    let main = CStr::from_bytes_with_nul_unchecked(b"main\0");
    let stages = [
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX)
            .module(vert_module)
            .name(main),
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT)
            .module(frag_module)
            .name(main),
    ];
    let vertex_input = vk::PipelineVertexInputStateCreateInfo::default();
    let input_assembly = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::POINT_LIST);
    let viewport_state = vk::PipelineViewportStateCreateInfo::default()
        .viewport_count(1)
        .scissor_count(1);
    let dynamic_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let dynamic_state =
        vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dynamic_states);
    let rasterization = vk::PipelineRasterizationStateCreateInfo::default()
        .depth_clamp_enable(false)
        .rasterizer_discard_enable(false)
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
        .line_width(1.0);
    let multisample = vk::PipelineMultisampleStateCreateInfo::default()
        .rasterization_samples(vk::SampleCountFlags::TYPE_1);
    let color_blend_attachment = vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(true)
        .src_color_blend_factor(vk::BlendFactor::SRC_ALPHA)
        .dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .color_blend_op(vk::BlendOp::ADD)
        .src_alpha_blend_factor(vk::BlendFactor::ONE)
        .dst_alpha_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .alpha_blend_op(vk::BlendOp::ADD)
        .color_write_mask(
            vk::ColorComponentFlags::R
                | vk::ColorComponentFlags::G
                | vk::ColorComponentFlags::B
                | vk::ColorComponentFlags::A,
        );
    let color_blend = vk::PipelineColorBlendStateCreateInfo::default()
        .attachments(slice::from_ref(&color_blend_attachment));
    let pipeline_info = vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&vertex_input)
        .input_assembly_state(&input_assembly)
        .viewport_state(&viewport_state)
        .dynamic_state(&dynamic_state)
        .rasterization_state(&rasterization)
        .multisample_state(&multisample)
        .color_blend_state(&color_blend)
        .layout(pipeline_layout)
        .render_pass(render_pass)
        .subpass(0);
    let pipelines = device
        .create_graphics_pipelines(
            vk::PipelineCache::null(),
            slice::from_ref(&pipeline_info),
            None,
        )
        .map_err(|(_, error)| format!("create-graphics-pipeline-{error:?}"))?;
    device.destroy_shader_module(frag_module, None);
    device.destroy_shader_module(vert_module, None);
    Ok(pipelines[0])
}

unsafe fn create_compute_pipeline(
    device: &ash::Device,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let comp_module = create_shader_module(
        device,
        include_bytes!(concat!(env!("OUT_DIR"), "/surface_particles.comp.spv")),
    )?;
    let main = CStr::from_bytes_with_nul_unchecked(b"main\0");
    let stage = vk::PipelineShaderStageCreateInfo::default()
        .stage(vk::ShaderStageFlags::COMPUTE)
        .module(comp_module)
        .name(main);
    let pipeline_info = vk::ComputePipelineCreateInfo::default()
        .stage(stage)
        .layout(pipeline_layout);
    let pipelines = device
        .create_compute_pipelines(
            vk::PipelineCache::null(),
            slice::from_ref(&pipeline_info),
            None,
        )
        .map_err(|(_, error)| format!("create-compute-pipeline-{error:?}"))?;
    device.destroy_shader_module(comp_module, None);
    Ok(pipelines[0])
}

unsafe fn create_private_surface_particle_descriptor_set_layout(
    device: &ash::Device,
) -> Result<vk::DescriptorSetLayout, String> {
    let bindings = [
        private_surface_particle_storage_binding(0),
        private_surface_particle_storage_binding(1),
        private_surface_particle_storage_binding(2),
        private_surface_particle_storage_binding(3),
        private_surface_particle_storage_binding(4),
        private_surface_particle_storage_binding(5),
        private_surface_particle_sampled_image_binding(6),
        private_surface_particle_storage_binding(8),
        private_surface_particle_storage_binding(9),
    ];
    let layout_info = vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings);
    device
        .create_descriptor_set_layout(&layout_info, None)
        .map_err(|error| format!("create-private-surface-particle-descriptor-set-layout-{error:?}"))
}

fn private_surface_particle_storage_binding(
    binding: u32,
) -> vk::DescriptorSetLayoutBinding<'static> {
    vk::DescriptorSetLayoutBinding::default()
        .binding(binding)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .descriptor_count(1)
        .stage_flags(
            vk::ShaderStageFlags::COMPUTE
                | vk::ShaderStageFlags::VERTEX
                | vk::ShaderStageFlags::FRAGMENT,
        )
}

fn private_surface_particle_sampled_image_binding(
    binding: u32,
) -> vk::DescriptorSetLayoutBinding<'static> {
    vk::DescriptorSetLayoutBinding::default()
        .binding(binding)
        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::FRAGMENT)
}

unsafe fn create_private_surface_particle_pipeline_layout(
    device: &ash::Device,
    descriptor_set_layout: vk::DescriptorSetLayout,
) -> Result<vk::PipelineLayout, String> {
    let push_range = vk::PushConstantRange::default()
        .stage_flags(
            vk::ShaderStageFlags::COMPUTE
                | vk::ShaderStageFlags::VERTEX
                | vk::ShaderStageFlags::FRAGMENT,
        )
        .offset(0)
        .size(mem::size_of::<SurfaceParticlePrivatePush>() as u32);
    let set_layouts = [descriptor_set_layout];
    let layout_info = vk::PipelineLayoutCreateInfo::default()
        .set_layouts(&set_layouts)
        .push_constant_ranges(slice::from_ref(&push_range));
    device
        .create_pipeline_layout(&layout_info, None)
        .map_err(|error| format!("create-private-surface-particle-pipeline-layout-{error:?}"))
}

unsafe fn create_private_surface_particle_compute_pipeline(
    device: &ash::Device,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let comp_module = create_shader_module(
        device,
        surface_particle_private_payload::PRIVATE_SURFACE_PARTICLE_SHADER_SPIRV,
    )?;
    let main = CStr::from_bytes_with_nul_unchecked(b"main\0");
    let stage = vk::PipelineShaderStageCreateInfo::default()
        .stage(vk::ShaderStageFlags::COMPUTE)
        .module(comp_module)
        .name(main);
    let pipeline_info = vk::ComputePipelineCreateInfo::default()
        .stage(stage)
        .layout(pipeline_layout);
    let pipelines = device
        .create_compute_pipelines(
            vk::PipelineCache::null(),
            slice::from_ref(&pipeline_info),
            None,
        )
        .map_err(|(_, error)| {
            format!("create-private-surface-particle-compute-pipeline-{error:?}")
        });
    device.destroy_shader_module(comp_module, None);
    pipelines.map(|pipelines| pipelines[0])
}

unsafe fn create_private_surface_particle_graphics_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let vert_module = create_shader_module(
        device,
        include_bytes!(concat!(
            env!("OUT_DIR"),
            "/surface_private_particles.vert.spv"
        )),
    )?;
    let frag_module = create_shader_module(
        device,
        include_bytes!(concat!(
            env!("OUT_DIR"),
            "/surface_private_particles.frag.spv"
        )),
    )?;
    let main = CStr::from_bytes_with_nul_unchecked(b"main\0");
    let stages = [
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX)
            .module(vert_module)
            .name(main),
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT)
            .module(frag_module)
            .name(main),
    ];
    let vertex_input = vk::PipelineVertexInputStateCreateInfo::default();
    let input_assembly = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);
    let viewport_state = vk::PipelineViewportStateCreateInfo::default()
        .viewport_count(1)
        .scissor_count(1);
    let dynamic_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let dynamic_state =
        vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dynamic_states);
    let rasterization = vk::PipelineRasterizationStateCreateInfo::default()
        .depth_clamp_enable(false)
        .rasterizer_discard_enable(false)
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
        .line_width(1.0);
    let multisample = vk::PipelineMultisampleStateCreateInfo::default()
        .rasterization_samples(vk::SampleCountFlags::TYPE_1);
    let color_blend_attachment = vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(true)
        .src_color_blend_factor(vk::BlendFactor::ONE)
        .dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .color_blend_op(vk::BlendOp::ADD)
        .src_alpha_blend_factor(vk::BlendFactor::ONE)
        .dst_alpha_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .alpha_blend_op(vk::BlendOp::ADD)
        .color_write_mask(
            vk::ColorComponentFlags::R
                | vk::ColorComponentFlags::G
                | vk::ColorComponentFlags::B
                | vk::ColorComponentFlags::A,
        );
    let color_blend = vk::PipelineColorBlendStateCreateInfo::default()
        .attachments(slice::from_ref(&color_blend_attachment));
    let pipeline_info = vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&vertex_input)
        .input_assembly_state(&input_assembly)
        .viewport_state(&viewport_state)
        .dynamic_state(&dynamic_state)
        .rasterization_state(&rasterization)
        .multisample_state(&multisample)
        .color_blend_state(&color_blend)
        .layout(pipeline_layout)
        .render_pass(render_pass)
        .subpass(0);
    let pipelines = device
        .create_graphics_pipelines(
            vk::PipelineCache::null(),
            slice::from_ref(&pipeline_info),
            None,
        )
        .map_err(|(_, error)| {
            format!("create-private-surface-particle-graphics-pipeline-{error:?}")
        })?;
    device.destroy_shader_module(frag_module, None);
    device.destroy_shader_module(vert_module, None);
    Ok(pipelines[0])
}

unsafe fn create_private_surface_particle_descriptor_pool(
    device: &ash::Device,
) -> Result<vk::DescriptorPool, String> {
    let pool_sizes = [
        vk::DescriptorPoolSize::default()
            .ty(vk::DescriptorType::STORAGE_BUFFER)
            .descriptor_count(
                (8 * PRIVATE_SURFACE_PARTICLE_DESCRIPTOR_SET_COUNT)
                    .try_into()
                    .expect("private storage descriptor count fits u32"),
            ),
        vk::DescriptorPoolSize::default()
            .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .descriptor_count(
                PRIVATE_SURFACE_PARTICLE_DESCRIPTOR_SET_COUNT
                    .try_into()
                    .expect("private sampled image descriptor count fits u32"),
            ),
    ];
    let pool_info = vk::DescriptorPoolCreateInfo::default()
        .pool_sizes(&pool_sizes)
        .max_sets(
            PRIVATE_SURFACE_PARTICLE_DESCRIPTOR_SET_COUNT
                .try_into()
                .expect("private descriptor set count fits u32"),
        );
    device
        .create_descriptor_pool(&pool_info, None)
        .map_err(|error| format!("create-private-surface-particle-descriptor-pool-{error:?}"))
}

unsafe fn allocate_private_surface_particle_descriptor_sets(
    device: &ash::Device,
    descriptor_pool: vk::DescriptorPool,
    descriptor_set_layout: vk::DescriptorSetLayout,
) -> Result<[vk::DescriptorSet; PRIVATE_SURFACE_PARTICLE_DESCRIPTOR_SET_COUNT], String> {
    let set_layouts = [descriptor_set_layout; PRIVATE_SURFACE_PARTICLE_DESCRIPTOR_SET_COUNT];
    let allocate_info = vk::DescriptorSetAllocateInfo::default()
        .descriptor_pool(descriptor_pool)
        .set_layouts(&set_layouts);
    let descriptor_sets = device
        .allocate_descriptor_sets(&allocate_info)
        .map_err(|error| format!("allocate-private-surface-particle-descriptor-sets-{error:?}"))?;
    if descriptor_sets.len() != PRIVATE_SURFACE_PARTICLE_DESCRIPTOR_SET_COUNT {
        return Err(format!(
            "allocate-private-surface-particle-descriptor-sets-count-{}",
            descriptor_sets.len()
        ));
    }
    Ok([descriptor_sets[0], descriptor_sets[1]])
}

unsafe fn update_private_surface_particle_descriptor_set(
    device: &ash::Device,
    descriptor_set: vk::DescriptorSet,
    payload_resources: &SurfaceParticlePrivatePayloadGpuResources,
    particle_output: vk::DescriptorBufferInfo,
    phase_source: vk::DescriptorBufferInfo,
    phase_target: vk::DescriptorBufferInfo,
    driver_bank: vk::DescriptorBufferInfo,
    diagnostics: vk::DescriptorBufferInfo,
) {
    let positions = [payload_resources.positions_descriptor()];
    let normals = [payload_resources.normals_descriptor()];
    let particle_output = [particle_output];
    let phase_source = [phase_source];
    let phase_target = [phase_target];
    let aux0 = [payload_resources.aux0_descriptor()];
    let mask_texture = [payload_resources.mask_texture_descriptor()];
    let driver_bank = [driver_bank];
    let diagnostics = [diagnostics];
    let writes = [
        private_surface_particle_storage_write(descriptor_set, 0, &positions),
        private_surface_particle_storage_write(descriptor_set, 1, &normals),
        private_surface_particle_storage_write(descriptor_set, 2, &particle_output),
        private_surface_particle_storage_write(descriptor_set, 3, &phase_source),
        private_surface_particle_storage_write(descriptor_set, 4, &phase_target),
        private_surface_particle_storage_write(descriptor_set, 5, &aux0),
        private_surface_particle_sampled_image_write(descriptor_set, 6, &mask_texture),
        private_surface_particle_storage_write(descriptor_set, 8, &driver_bank),
        private_surface_particle_storage_write(descriptor_set, 9, &diagnostics),
    ];
    device.update_descriptor_sets(&writes, &[]);
}

fn private_surface_particle_storage_write<'a>(
    descriptor_set: vk::DescriptorSet,
    binding: u32,
    buffer_info: &'a [vk::DescriptorBufferInfo],
) -> vk::WriteDescriptorSet<'a> {
    vk::WriteDescriptorSet::default()
        .dst_set(descriptor_set)
        .dst_binding(binding)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .buffer_info(buffer_info)
}

fn private_surface_particle_sampled_image_write<'a>(
    descriptor_set: vk::DescriptorSet,
    binding: u32,
    image_info: &'a [vk::DescriptorImageInfo],
) -> vk::WriteDescriptorSet<'a> {
    vk::WriteDescriptorSet::default()
        .dst_set(descriptor_set)
        .dst_binding(binding)
        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .image_info(image_info)
}

unsafe fn create_descriptor_pool(device: &ash::Device) -> Result<vk::DescriptorPool, String> {
    let pool_size = vk::DescriptorPoolSize::default()
        .ty(vk::DescriptorType::STORAGE_BUFFER)
        .descriptor_count(1);
    let pool_info = vk::DescriptorPoolCreateInfo::default()
        .pool_sizes(slice::from_ref(&pool_size))
        .max_sets(1);
    device
        .create_descriptor_pool(&pool_info, None)
        .map_err(|error| format!("create-descriptor-pool-{error:?}"))
}

unsafe fn create_particle_descriptor_set(
    device: &ash::Device,
    descriptor_pool: vk::DescriptorPool,
    descriptor_set_layout: vk::DescriptorSetLayout,
    particle_buffer: vk::Buffer,
    particle_buffer_size: vk::DeviceSize,
) -> Result<vk::DescriptorSet, String> {
    let set_layouts = [descriptor_set_layout];
    let allocate_info = vk::DescriptorSetAllocateInfo::default()
        .descriptor_pool(descriptor_pool)
        .set_layouts(&set_layouts);
    let descriptor_set = device
        .allocate_descriptor_sets(&allocate_info)
        .map_err(|error| format!("allocate-descriptor-set-{error:?}"))?[0];
    let buffer_info = vk::DescriptorBufferInfo::default()
        .buffer(particle_buffer)
        .offset(0)
        .range(particle_buffer_size);
    let write = vk::WriteDescriptorSet::default()
        .dst_set(descriptor_set)
        .dst_binding(0)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .buffer_info(slice::from_ref(&buffer_info));
    device.update_descriptor_sets(slice::from_ref(&write), &[]);
    Ok(descriptor_set)
}

unsafe fn create_buffer_with_memory(
    instance: &ash::Instance,
    device: &ash::Device,
    physical_device: vk::PhysicalDevice,
    size: vk::DeviceSize,
    usage: vk::BufferUsageFlags,
    properties: vk::MemoryPropertyFlags,
) -> Result<(vk::Buffer, vk::DeviceMemory), String> {
    let buffer_info = vk::BufferCreateInfo::default()
        .size(size)
        .usage(usage)
        .sharing_mode(vk::SharingMode::EXCLUSIVE);
    let buffer = device
        .create_buffer(&buffer_info, None)
        .map_err(|error| format!("create-buffer-{error:?}"))?;
    let requirements = device.get_buffer_memory_requirements(buffer);
    let memory_type_index = find_memory_type_index(
        instance,
        physical_device,
        requirements.memory_type_bits,
        properties,
    )
    .ok_or_else(|| {
        device.destroy_buffer(buffer, None);
        format!("no-memory-type-for-{properties:?}")
    })?;
    let allocate_info = vk::MemoryAllocateInfo::default()
        .allocation_size(requirements.size)
        .memory_type_index(memory_type_index);
    let memory = device
        .allocate_memory(&allocate_info, None)
        .map_err(|error| {
            device.destroy_buffer(buffer, None);
            format!("allocate-buffer-memory-{error:?}")
        })?;
    device
        .bind_buffer_memory(buffer, memory, 0)
        .map_err(|error| {
            device.free_memory(memory, None);
            device.destroy_buffer(buffer, None);
            format!("bind-buffer-memory-{error:?}")
        })?;
    Ok((buffer, memory))
}

unsafe fn create_private_payload_buffer(
    instance: &ash::Instance,
    device: &ash::Device,
    physical_device: vk::PhysicalDevice,
    bytes: &[u8],
    label: &str,
) -> Result<(vk::Buffer, vk::DeviceMemory), String> {
    if bytes.is_empty() {
        return Err(format!("private-payload-{label}-empty"));
    }
    let size = bytes.len() as vk::DeviceSize;
    let (buffer, memory) = create_buffer_with_memory(
        instance,
        device,
        physical_device,
        size,
        vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
        vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
    )?;
    let mapped = device
        .map_memory(memory, 0, size, vk::MemoryMapFlags::empty())
        .map_err(|error| {
            device.destroy_buffer(buffer, None);
            device.free_memory(memory, None);
            format!("map-private-payload-{label}-{error:?}")
        })?;
    ptr::copy_nonoverlapping(bytes.as_ptr(), mapped.cast::<u8>(), bytes.len());
    device.unmap_memory(memory);
    Ok((buffer, memory))
}

unsafe fn upload_private_surface_particle_mask_texture(
    device: &ash::Device,
    queue: vk::Queue,
    command_pool: vk::CommandPool,
    staging_buffer: vk::Buffer,
    image: vk::Image,
    width: u32,
    height: u32,
    layers: u32,
) -> Result<(), String> {
    let allocate_info = vk::CommandBufferAllocateInfo::default()
        .command_pool(command_pool)
        .level(vk::CommandBufferLevel::PRIMARY)
        .command_buffer_count(1);
    let command_buffer = device
        .allocate_command_buffers(&allocate_info)
        .map_err(|error| format!("allocate-mask-upload-command-buffer-{error:?}"))?[0];
    let begin_info =
        vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
    if let Err(error) = device.begin_command_buffer(command_buffer, &begin_info) {
        device.free_command_buffers(command_pool, slice::from_ref(&command_buffer));
        return Err(format!("begin-mask-upload-command-buffer-{error:?}"));
    }

    let subresource_range = private_surface_particle_mask_subresource_range(layers);
    let transfer_barrier = vk::ImageMemoryBarrier::default()
        .old_layout(vk::ImageLayout::UNDEFINED)
        .new_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL)
        .src_access_mask(vk::AccessFlags::empty())
        .dst_access_mask(vk::AccessFlags::TRANSFER_WRITE)
        .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .image(image)
        .subresource_range(subresource_range);
    device.cmd_pipeline_barrier(
        command_buffer,
        vk::PipelineStageFlags::TOP_OF_PIPE,
        vk::PipelineStageFlags::TRANSFER,
        vk::DependencyFlags::empty(),
        &[],
        &[],
        slice::from_ref(&transfer_barrier),
    );
    let copy_region = vk::BufferImageCopy::default()
        .buffer_offset(0)
        .buffer_row_length(0)
        .buffer_image_height(0)
        .image_subresource(
            vk::ImageSubresourceLayers::default()
                .aspect_mask(vk::ImageAspectFlags::COLOR)
                .mip_level(0)
                .base_array_layer(0)
                .layer_count(layers),
        )
        .image_offset(vk::Offset3D { x: 0, y: 0, z: 0 })
        .image_extent(vk::Extent3D {
            width,
            height,
            depth: 1,
        });
    device.cmd_copy_buffer_to_image(
        command_buffer,
        staging_buffer,
        image,
        vk::ImageLayout::TRANSFER_DST_OPTIMAL,
        slice::from_ref(&copy_region),
    );
    let shader_barrier = vk::ImageMemoryBarrier::default()
        .old_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL)
        .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
        .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_READ)
        .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .image(image)
        .subresource_range(subresource_range);
    device.cmd_pipeline_barrier(
        command_buffer,
        vk::PipelineStageFlags::TRANSFER,
        vk::PipelineStageFlags::FRAGMENT_SHADER,
        vk::DependencyFlags::empty(),
        &[],
        &[],
        slice::from_ref(&shader_barrier),
    );
    if let Err(error) = device.end_command_buffer(command_buffer) {
        device.free_command_buffers(command_pool, slice::from_ref(&command_buffer));
        return Err(format!("end-mask-upload-command-buffer-{error:?}"));
    }
    let submit_info = [vk::SubmitInfo::default().command_buffers(slice::from_ref(&command_buffer))];
    let submit_result = device.queue_submit(queue, &submit_info, vk::Fence::null());
    if let Err(error) = submit_result {
        device.free_command_buffers(command_pool, slice::from_ref(&command_buffer));
        return Err(format!("submit-mask-upload-command-buffer-{error:?}"));
    }
    let wait_result = device.queue_wait_idle(queue);
    device.free_command_buffers(command_pool, slice::from_ref(&command_buffer));
    wait_result.map_err(|error| format!("wait-mask-upload-queue-idle-{error:?}"))
}

fn private_surface_particle_mask_subresource_range(layers: u32) -> vk::ImageSubresourceRange {
    vk::ImageSubresourceRange::default()
        .aspect_mask(vk::ImageAspectFlags::COLOR)
        .base_mip_level(0)
        .level_count(1)
        .base_array_layer(0)
        .layer_count(layers)
}

unsafe fn create_private_zeroed_storage_buffer(
    instance: &ash::Instance,
    device: &ash::Device,
    physical_device: vk::PhysicalDevice,
    size: vk::DeviceSize,
    label: &str,
) -> Result<SurfaceParticlePrivateBuffer, String> {
    if size == 0 {
        return Err(format!("private-zeroed-storage-{label}-empty"));
    }
    let (buffer, memory) = create_buffer_with_memory(
        instance,
        device,
        physical_device,
        size,
        vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
        vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
    )?;
    let mapped = device
        .map_memory(memory, 0, size, vk::MemoryMapFlags::empty())
        .map_err(|error| {
            device.destroy_buffer(buffer, None);
            device.free_memory(memory, None);
            format!("map-private-zeroed-storage-{label}-{error:?}")
        })?;
    ptr::write_bytes(mapped.cast::<u8>(), 0, size as usize);
    device.unmap_memory(memory);
    Ok(SurfaceParticlePrivateBuffer {
        buffer,
        memory,
        bytes: size,
    })
}

unsafe fn find_memory_type_index(
    instance: &ash::Instance,
    physical_device: vk::PhysicalDevice,
    type_bits: u32,
    properties: vk::MemoryPropertyFlags,
) -> Option<u32> {
    let memory_properties = instance.get_physical_device_memory_properties(physical_device);
    for index in 0..memory_properties.memory_type_count {
        let supported = (type_bits & (1 << index)) != 0;
        let has_properties = memory_properties.memory_types[index as usize]
            .property_flags
            .contains(properties);
        if supported && has_properties {
            return Some(index);
        }
    }
    None
}

unsafe fn create_shader_module(
    device: &ash::Device,
    bytes: &[u8],
) -> Result<vk::ShaderModule, String> {
    if bytes.is_empty() || bytes.len() % mem::size_of::<u32>() != 0 {
        return Err("invalid-spirv-length".to_string());
    }
    let code = bytes
        .chunks_exact(mem::size_of::<u32>())
        .map(|word| u32::from_le_bytes([word[0], word[1], word[2], word[3]]))
        .collect::<Vec<_>>();
    let shader_info = vk::ShaderModuleCreateInfo::default().code(&code);
    device
        .create_shader_module(&shader_info, None)
        .map_err(|error| format!("create-shader-module-{error:?}"))
}

#[repr(C)]
#[derive(Clone, Copy)]
struct SurfaceParticlePush {
    time_seconds: f32,
    aspect: f32,
    point_size_pixels: f32,
    particle_count: u32,
    driver0_value01: f32,
    driver1_value01: f32,
    point_scale: f32,
    reserved0: f32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct SurfaceParticlePrivatePush {
    params0: [f32; 4],
    params1: [f32; 4],
    transparency_params: [f32; 4],
    tracer_params: [f32; 4],
    world_center_scale: [f32; 4],
    eye_position: [f32; 4],
    eye_orientation_xyzw: [f32; 4],
    fov_tangents: [f32; 4],
}

impl SurfaceParticlePrivatePush {
    fn main_compute(
        particle_count: u32,
        tracer_state_capacity: u32,
        draw_count: u32,
        frame_counter: u32,
        time_seconds: f32,
        params: SurfaceParticleParameters,
        panel_projection: ReplayHandPanelProjection,
    ) -> Self {
        let phase_frame = if frame_counter < 2 {
            0.0
        } else {
            frame_counter as f32
        };
        let sphere_scale = DEFAULT_SURFACE_PARTICLE_SIM_WORLD_RADIUS_METERS
            * params.projection_world_scale.clamp(0.5, 2.0);
        let world_anchor = current_surface_particle_world_anchor(panel_projection);
        Self {
            params0: [
                particle_count as f32,
                params.point_scale.clamp(0.05, 1.5),
                0.0,
                params.driver0_value01.clamp(0.0, 1.0),
            ],
            params1: [
                phase_frame,
                1.0 / 90.0,
                time_seconds.max(0.0),
                params.driver1_value01.clamp(0.0, 1.0),
            ],
            transparency_params: [params.transparency_opacity.clamp(0.0, 1.0), 1.0, 0.0, 0.0],
            tracer_params: [
                draw_count as f32,
                tracer_state_capacity as f32,
                params.tracer_lifetime_seconds.clamp(0.0, 0.5),
                if tracer_state_capacity > 0 {
                    params.tracer_copies_per_second.clamp(0.0, 14.0)
                } else {
                    0.0
                },
            ],
            world_center_scale: [
                world_anchor.center[0],
                world_anchor.center[1],
                world_anchor.center[2],
                sphere_scale,
            ],
            eye_position: [
                world_anchor.right[0],
                world_anchor.right[1],
                world_anchor.right[2],
                if world_anchor.valid { 1.0 } else { 0.0 },
            ],
            eye_orientation_xyzw: [
                world_anchor.up[0],
                world_anchor.up[1],
                world_anchor.up[2],
                0.0,
            ],
            fov_tangents: [
                world_anchor.forward[0],
                world_anchor.forward[1],
                world_anchor.forward[2],
                0.0,
            ],
        }
    }

    fn main_draw(
        particle_count: u32,
        tracer_state_capacity: u32,
        draw_count: u32,
        frame_counter: u32,
        eye_index: u32,
        time_seconds: f32,
        params: SurfaceParticleParameters,
        panel_projection: ReplayHandPanelProjection,
    ) -> Self {
        let mut push = Self::main_compute(
            particle_count,
            tracer_state_capacity,
            draw_count,
            frame_counter,
            time_seconds,
            params,
            panel_projection,
        );
        let draw_projection =
            current_surface_particle_spatial_world_draw_camera_projection(panel_projection);
        let eye_world = current_surface_particle_eye_world(draw_projection, eye_index.min(1));
        push.params0[2] = eye_index.min(1) as f32;
        push.params1 = [eye_world[0], eye_world[1], eye_world[2], 1.0];
        push.world_center_scale = [
            draw_projection.center[0],
            draw_projection.center[1],
            draw_projection.center[2],
            push.world_center_scale[3],
        ];
        push.eye_position = [
            draw_projection.right[0],
            draw_projection.right[1],
            draw_projection.right[2],
            draw_projection.width_meters,
        ];
        push.eye_orientation_xyzw = [
            draw_projection.up[0],
            draw_projection.up[1],
            draw_projection.up[2],
            draw_projection.height_meters,
        ];
        push.fov_tangents = main_draw_panel_forward_distance(
            draw_projection.right,
            draw_projection.up,
            draw_projection.target_distance_meters,
        );
        push
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
struct SurfaceParticleRow {
    position_size: [f32; 4],
    color: [f32; 4],
}

unsafe fn record_command_buffer(
    device: &ash::Device,
    command_buffer: vk::CommandBuffer,
    render_pass: vk::RenderPass,
    framebuffer: vk::Framebuffer,
    extent: vk::Extent2D,
    _pipeline: vk::Pipeline,
    compute_pipeline: vk::Pipeline,
    pipeline_layout: vk::PipelineLayout,
    descriptor_set: vk::DescriptorSet,
    particle_count: u32,
    frame_counter: u32,
    time_seconds: f32,
    renderer: &mut SurfaceParticleRenderer,
    private_pipeline_resources: Option<&SurfaceParticlePrivatePipelineResources>,
    mut private_descriptor_resources: Option<&mut SurfaceParticlePrivateDescriptorResources>,
) -> Result<(), String> {
    device
        .reset_command_buffer(command_buffer, vk::CommandBufferResetFlags::empty())
        .map_err(|error| format!("reset-command-buffer-{error:?}"))?;
    let begin_info = vk::CommandBufferBeginInfo::default();
    device
        .begin_command_buffer(command_buffer, &begin_info)
        .map_err(|error| format!("begin-command-buffer-{error:?}"))?;
    let params = current_surface_particle_parameters();
    let panel_projection = current_surface_particle_panel_projection();
    let stereo_layout = surface_particle_stereo_layout(extent);
    let record_public_proof_compute =
        renderer.mode() != SurfaceParticleRendererMode::PrivateMainDrawOnly;
    if let (Some(private_pipeline_resources), Some(private_descriptor_resources)) = (
        private_pipeline_resources,
        private_descriptor_resources.as_deref_mut(),
    ) {
        private_descriptor_resources.record_main_compute_dispatch(
            device,
            command_buffer,
            private_pipeline_resources,
            frame_counter,
            time_seconds,
            params,
            panel_projection,
        );
    }
    if record_public_proof_compute {
        let point_size_pixels = (extent.height as f32 * 0.018).clamp(8.0, 36.0);
        let push = SurfaceParticlePush {
            time_seconds,
            aspect: stereo_layout.per_eye_extent.width as f32
                / stereo_layout.per_eye_extent.height.max(1) as f32,
            point_size_pixels,
            particle_count,
            driver0_value01: params.driver0_value01,
            driver1_value01: params.driver1_value01,
            point_scale: params.point_scale,
            reserved0: 0.0,
        };
        let push_bytes = slice::from_raw_parts(
            (&push as *const SurfaceParticlePush).cast::<u8>(),
            mem::size_of::<SurfaceParticlePush>(),
        );
        device.cmd_bind_pipeline(
            command_buffer,
            vk::PipelineBindPoint::COMPUTE,
            compute_pipeline,
        );
        device.cmd_bind_descriptor_sets(
            command_buffer,
            vk::PipelineBindPoint::COMPUTE,
            pipeline_layout,
            0,
            slice::from_ref(&descriptor_set),
            &[],
        );
        device.cmd_push_constants(
            command_buffer,
            pipeline_layout,
            vk::ShaderStageFlags::COMPUTE,
            0,
            push_bytes,
        );
        let workgroup_count = particle_count
            .saturating_add(SURFACE_PARTICLE_COMPUTE_LOCAL_SIZE - 1)
            / SURFACE_PARTICLE_COMPUTE_LOCAL_SIZE;
        device.cmd_dispatch(command_buffer, workgroup_count.max(1), 1, 1);
        let compute_to_vertex_barrier = vk::MemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::SHADER_WRITE)
            .dst_access_mask(vk::AccessFlags::SHADER_READ);
        device.cmd_pipeline_barrier(
            command_buffer,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::VERTEX_SHADER,
            vk::DependencyFlags::empty(),
            slice::from_ref(&compute_to_vertex_barrier),
            &[],
            &[],
        );
    }
    let clear = vk::ClearValue {
        color: vk::ClearColorValue {
            float32: [0.02, 0.035, 0.055, 1.0],
        },
    };
    let render_area = vk::Rect2D::default().extent(extent);
    let render_pass_info = vk::RenderPassBeginInfo::default()
        .render_pass(render_pass)
        .framebuffer(framebuffer)
        .render_area(render_area)
        .clear_values(slice::from_ref(&clear));
    device.cmd_begin_render_pass(
        command_buffer,
        &render_pass_info,
        vk::SubpassContents::INLINE,
    );
    for eye_index in 0..stereo_layout.eye_count {
        let x_offset = eye_index.saturating_mul(stereo_layout.per_eye_extent.width);
        let viewport = vk::Viewport::default()
            .x(x_offset as f32)
            .y(0.0)
            .width(stereo_layout.per_eye_extent.width as f32)
            .height(stereo_layout.per_eye_extent.height as f32)
            .min_depth(0.0)
            .max_depth(1.0);
        let scissor = vk::Rect2D::default()
            .offset(vk::Offset2D {
                x: x_offset as i32,
                y: 0,
            })
            .extent(stereo_layout.per_eye_extent);
        device.cmd_set_viewport(command_buffer, 0, slice::from_ref(&viewport));
        device.cmd_set_scissor(command_buffer, 0, slice::from_ref(&scissor));
        renderer.record_eye(
            device,
            command_buffer,
            frame_counter,
            eye_index,
            time_seconds,
            panel_projection,
            params.driver0_value01,
            params.driver1_value01,
            params.point_scale,
            params.live_hand_depth_offset_meters,
            params.diagnostic_mode,
        );
        if let (Some(private_pipeline_resources), Some(private_descriptor_resources)) = (
            private_pipeline_resources,
            private_descriptor_resources.as_deref(),
        ) {
            private_descriptor_resources.record_main_draw(
                device,
                command_buffer,
                private_pipeline_resources,
                frame_counter,
                eye_index,
                time_seconds,
                params,
                panel_projection,
            );
        }
    }
    device.cmd_end_render_pass(command_buffer);
    device
        .end_command_buffer(command_buffer)
        .map_err(|error| format!("end-command-buffer-{error:?}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_vec3_near(actual: [f32; 3], expected: [f32; 3]) {
        for (actual, expected) in actual.into_iter().zip(expected) {
            assert!((actual - expected).abs() < 0.0001, "{actual} != {expected}");
        }
    }

    fn test_params() -> SurfaceParticleParameters {
        SurfaceParticleParameters {
            revision: 0,
            driver0_value01: 0.0,
            driver1_value01: 0.0,
            driver2_value01: 0.0,
            driver3_value01: 0.0,
            driver4_value01: 0.0,
            driver5_value01: 0.0,
            driver6_value01: 0.0,
            driver7_value01: 0.0,
            point_scale: 1.0,
            tracer_draw_slots_per_oscillator: 0.0,
            tracer_lifetime_seconds: 0.0,
            tracer_copies_per_second: 0.0,
            transparency_opacity: 1.0,
            projection_world_scale: 1.0,
            live_hand_depth_offset_meters: 0.0,
            diagnostic_mode: 0,
        }
    }

    fn test_panel_projection(
        center: [f32; 3],
        right: [f32; 3],
        up: [f32; 3],
    ) -> ReplayHandPanelProjection {
        ReplayHandPanelProjection {
            center,
            right,
            up,
            width_meters: 4.0,
            height_meters: 4.0,
            target_distance_meters: 2.0,
            left_eye_offset_right_meters: DEFAULT_SURFACE_PARTICLE_LEFT_EYE_OFFSET_RIGHT_METERS,
            right_eye_offset_right_meters: DEFAULT_SURFACE_PARTICLE_RIGHT_EYE_OFFSET_RIGHT_METERS,
            valid: true,
        }
    }

    fn store_test_viewer_pose(viewer: [f32; 3], right: [f32; 3], up: [f32; 3], forward: [f32; 3]) {
        SURFACE_PARTICLE_VIEWER_WORLD_X_BITS.store(viewer[0].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_VIEWER_WORLD_Y_BITS.store(viewer[1].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_VIEWER_WORLD_Z_BITS.store(viewer[2].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_VIEWER_RIGHT_X_BITS.store(right[0].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_VIEWER_RIGHT_Y_BITS.store(right[1].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_VIEWER_RIGHT_Z_BITS.store(right[2].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_VIEWER_UP_X_BITS.store(up[0].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_VIEWER_UP_Y_BITS.store(up[1].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_VIEWER_UP_Z_BITS.store(up[2].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_VIEWER_FORWARD_X_BITS.store(forward[0].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_VIEWER_FORWARD_Y_BITS.store(forward[1].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_VIEWER_FORWARD_Z_BITS.store(forward[2].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_LEFT_EYE_WORLD_X_BITS.store(viewer[0].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_LEFT_EYE_WORLD_Y_BITS.store(viewer[1].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_LEFT_EYE_WORLD_Z_BITS.store(viewer[2].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_RIGHT_EYE_WORLD_X_BITS.store(viewer[0].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_RIGHT_EYE_WORLD_Y_BITS.store(viewer[1].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_RIGHT_EYE_WORLD_Z_BITS.store(viewer[2].to_bits(), Ordering::Relaxed);
        SURFACE_PARTICLE_VIEWER_EYE_POSE_VALID.store(true, Ordering::Relaxed);
    }

    #[test]
    fn auto_recenter_moves_sphere_center_to_viewer_after_half_meter_drift() {
        reset_surface_particle_world_anchor();
        store_surface_particle_world_anchor_canonical([0.0, 1.4, -2.0]);

        let recentered =
            maybe_auto_recenter_surface_particle_world_anchor_on_viewer([0.0, 1.4, -1.0]);

        assert!(recentered);
        let push = SurfaceParticlePrivatePush::main_compute(
            128,
            0,
            128,
            3,
            0.0,
            test_params(),
            test_panel_projection([0.0, 1.4, -2.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]),
        );
        assert_vec3_near(
            [
                push.world_center_scale[0],
                push.world_center_scale[1],
                push.world_center_scale[2],
            ],
            [0.0, 1.4, -1.0],
        );
        assert_vec3_near(
            [
                push.eye_position[0],
                push.eye_position[1],
                push.eye_position[2],
            ],
            [1.0, 0.0, 0.0],
        );
        assert_eq!(push.eye_position[3], 1.0);
    }

    #[test]
    fn auto_recenter_keeps_sphere_center_when_viewer_stays_within_half_meter() {
        reset_surface_particle_world_anchor();
        store_surface_particle_world_anchor_canonical([0.0, 1.4, 0.0]);

        let recentered =
            maybe_auto_recenter_surface_particle_world_anchor_on_viewer([0.49, 1.4, 0.0]);

        assert!(!recentered);
        assert_vec3_near(
            configured_surface_particle_world_anchor_center(),
            [0.0, 1.4, 0.0],
        );
    }

    #[test]
    fn draw_camera_projection_uses_viewer_forward_not_carrier_panel_yaw() {
        reset_surface_particle_world_anchor();
        store_test_viewer_pose(
            [0.0, 1.4, 0.0],
            [1.0, 0.0, 0.0],
            [0.0, 1.0, 0.0],
            [0.0, 0.0, -1.0],
        );
        let carrier_panel =
            test_panel_projection([1.0, 1.4, -2.0], [0.0, 0.0, 1.0], [0.0, 1.0, 0.0]);

        let draw_projection =
            current_surface_particle_spatial_world_draw_camera_projection(carrier_panel);

        assert_vec3_near(
            surface_particle_panel_forward(carrier_panel),
            [1.0, 0.0, 0.0],
        );
        assert_vec3_near(draw_projection.center, [0.0, 1.4, -2.0]);
        assert_vec3_near(draw_projection.right, [1.0, 0.0, 0.0]);
        assert_vec3_near(draw_projection.up, [0.0, 1.0, 0.0]);
        assert_vec3_near(
            surface_particle_panel_forward(draw_projection),
            [0.0, 0.0, -1.0],
        );
    }

    #[test]
    fn draw_camera_projection_corrects_horizontal_viewer_yaw_mirror() {
        reset_surface_particle_world_anchor();
        store_test_viewer_pose(
            [0.0, 1.4, 0.0],
            [0.0, 0.0, 1.0],
            [0.0, 1.0, 0.0],
            [1.0, 0.0, 0.0],
        );
        let carrier_panel =
            test_panel_projection([0.0, 1.4, -2.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]);

        let draw_projection =
            current_surface_particle_spatial_world_draw_camera_projection(carrier_panel);

        assert_vec3_near(
            surface_particle_panel_forward(carrier_panel),
            [0.0, 0.0, -1.0],
        );
        assert_vec3_near(draw_projection.center, [-2.0, 1.4, 0.0]);
        assert_vec3_near(draw_projection.right, [0.0, 0.0, -1.0]);
        assert_vec3_near(draw_projection.up, [0.0, 1.0, 0.0]);
        assert_vec3_near(
            surface_particle_panel_forward(draw_projection),
            [-1.0, 0.0, 0.0],
        );
    }

    #[test]
    fn draw_camera_projection_preserves_viewer_pitch_and_yaw_without_roll() {
        reset_surface_particle_world_anchor();
        store_test_viewer_pose(
            [0.0, 1.4, 0.0],
            [0.0, 0.0, 1.0],
            [0.0, 1.0, 0.0],
            [0.8660254, -0.5, 0.0],
        );
        let carrier_panel =
            test_panel_projection([0.0, 1.4, -2.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]);

        let draw_projection =
            current_surface_particle_spatial_world_draw_camera_projection(carrier_panel);

        assert_vec3_near(draw_projection.center, [-1.7320508, 0.4, 0.0]);
        assert_vec3_near(draw_projection.right, [0.0, 0.0, -1.0]);
        assert_vec3_near(draw_projection.up, [-0.5, 0.8660254, 0.0]);
        assert_vec3_near(
            surface_particle_panel_forward(draw_projection),
            [-0.8660254, -0.5, 0.0],
        );
    }

    #[test]
    fn private_main_draw_push_uses_viewer_forward_camera_basis_not_carrier_panel_yaw() {
        reset_surface_particle_world_anchor();
        store_test_viewer_pose(
            [0.0, 1.4, 0.0],
            [1.0, 0.0, 0.0],
            [0.0, 1.0, 0.0],
            [0.0, 0.0, -1.0],
        );
        let carrier_panel =
            test_panel_projection([1.0, 1.4, -2.0], [0.0, 0.0, 1.0], [0.0, 1.0, 0.0]);

        let push = SurfaceParticlePrivatePush::main_draw(
            128,
            0,
            128,
            3,
            0,
            1.0,
            test_params(),
            carrier_panel,
        );

        assert_vec3_near(
            [push.params1[0], push.params1[1], push.params1[2]],
            [0.0, 1.4, 0.0],
        );
        assert_vec3_near(
            [
                push.world_center_scale[0],
                push.world_center_scale[1],
                push.world_center_scale[2],
            ],
            [0.0, 1.4, -2.0],
        );
        assert_vec3_near(
            [
                push.eye_position[0],
                push.eye_position[1],
                push.eye_position[2],
            ],
            [1.0, 0.0, 0.0],
        );
        assert_vec3_near(
            [
                push.eye_orientation_xyzw[0],
                push.eye_orientation_xyzw[1],
                push.eye_orientation_xyzw[2],
            ],
            [0.0, 1.0, 0.0],
        );
        assert_vec3_near(
            [
                push.fov_tangents[0],
                push.fov_tangents[1],
                push.fov_tangents[2],
            ],
            [0.0, 0.0, -1.0],
        );
    }

    #[test]
    fn private_main_draw_eye_offsets_use_roll_stable_yawed_right_when_viewer_is_yawed() {
        reset_surface_particle_world_anchor();
        store_test_viewer_pose(
            [0.2, 1.4, 0.1],
            [0.0, 0.0, 1.0],
            [0.0, 1.0, 0.0],
            [1.0, 0.0, 0.0],
        );
        let carrier_panel =
            test_panel_projection([0.0, 1.4, -2.0], [0.0, 0.0, 1.0], [0.0, 1.0, 0.0]);

        let push = SurfaceParticlePrivatePush::main_draw(
            128,
            0,
            128,
            3,
            0,
            1.0,
            test_params(),
            carrier_panel,
        );

        assert_vec3_near(
            [push.params1[0], push.params1[1], push.params1[2]],
            [
                0.2,
                1.4,
                0.1 - DEFAULT_SURFACE_PARTICLE_LEFT_EYE_OFFSET_RIGHT_METERS,
            ],
        );
        assert_vec3_near(
            [
                push.eye_position[0],
                push.eye_position[1],
                push.eye_position[2],
            ],
            [0.0, 0.0, -1.0],
        );
    }
}
