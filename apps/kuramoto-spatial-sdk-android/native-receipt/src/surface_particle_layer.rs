use std::ffi::{c_void, CStr, CString};
use std::mem;
#[cfg(target_os = "android")]
use std::os::raw::c_char;
use std::os::raw::{c_float, c_int};
use std::slice;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::thread;
use std::time::Instant;

use ash::vk;

use crate::live_hand_joints::{store_live_hand_panel_basis, LiveHandOpenXrHandles};
use crate::replay_hands::{ReplayHandPanelProjection, ReplayHandsRenderer};
use crate::{android_log_info, bool_token, marker_token};

const START_RECEIVED: i64 = 1 << 0;
const START_SURFACE_NON_NULL: i64 = 1 << 1;
const START_NATIVE_WINDOW_OBTAINED: i64 = 1 << 2;
const START_RENDER_THREAD_SPAWNED: i64 = 1 << 3;

static STOP_SURFACE_PARTICLES: AtomicBool = AtomicBool::new(false);
static SURFACE_PARTICLE_DRIVER0_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_DRIVER1_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_POINT_SCALE_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_LIVE_HAND_DEPTH_OFFSET_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_DIAGNOSTIC_MODE: AtomicU32 = AtomicU32::new(0);
static SURFACE_PARTICLE_HOTLOAD_LOG_COUNT: AtomicU32 = AtomicU32::new(0);
static SURFACE_PARTICLE_PANEL_CENTER_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_CENTER_Y_BITS: AtomicU32 = AtomicU32::new(1.22_f32.to_bits());
static SURFACE_PARTICLE_PANEL_CENTER_Z_BITS: AtomicU32 = AtomicU32::new((-0.72_f32).to_bits());
static SURFACE_PARTICLE_PANEL_RIGHT_X_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_RIGHT_Y_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_RIGHT_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_UP_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_UP_Y_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_UP_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static SURFACE_PARTICLE_PANEL_WIDTH_BITS: AtomicU32 = AtomicU32::new(1.44_f32.to_bits());
static SURFACE_PARTICLE_PANEL_HEIGHT_BITS: AtomicU32 = AtomicU32::new(1.44_f32.to_bits());
static SURFACE_PARTICLE_PANEL_TARGET_DISTANCE_BITS: AtomicU32 = AtomicU32::new(0.72_f32.to_bits());
static SURFACE_PARTICLE_PANEL_POSE_VALID: AtomicBool = AtomicBool::new(false);
static SURFACE_PARTICLE_PANEL_POSE_LOG_COUNT: AtomicU32 = AtomicU32::new(0);

const SURFACE_PARTICLE_COMPUTE_LOCAL_SIZE: u32 = 64;
const SURFACE_PARTICLE_LIVE_HAND_DEPTH_OFFSET_PROPERTY: &str =
    "debug.rustyquest.kuramoto_spatial.live_hand_depth_offset_meters";
const SURFACE_PARTICLE_DIAGNOSTIC_MODE_PROPERTY: &str =
    "debug.rustyquest.kuramoto_spatial.particle_layer.diagnostic_mode";
const SURFACE_PARTICLE_PROJECTION_MARKERS: &str =
    "projectionContentMappingMode=world-to-spatial-sdk-panel-plane-left-right targetCoordinateSpace=spatial-sdk-surface-panel-eye-uv targetProjectionSpace=spatial-sdk-panel-plane-perspective-projection targetFovTangents=panel-plane-derived leftTargetSurfaceUvRect=0.0;0.0;1.0;1.0 rightTargetSurfaceUvRect=0.0;0.0;1.0;1.0 targetClipPolicy=clip-to-panel-eye-surface rendererSurfaceUvOrigin=native-vulkan-dynamic-viewport-scissor worldToPanelProjection=spatial-sdk-panel-plane-basis";

#[link(name = "android")]
extern "C" {
    fn ANativeWindow_fromSurface(env: *mut c_void, surface: *mut c_void) -> *mut vk::ANativeWindow;
    fn ANativeWindow_release(window: *mut vk::ANativeWindow);
}

#[cfg(target_os = "android")]
extern "C" {
    fn __system_property_get(name: *const c_char, value: *mut c_char) -> c_int;
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_kuramoto_1spatial_KuramotoSpatialActivity_nativeStartSurfaceParticleLayer(
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
        .name("kuramoto-spatial-surface-particles".to_string())
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
                ANativeWindow_release(window);
            }
            match result {
                Ok(stats) => {
                    let stereo_layout = surface_particle_stereo_layout(stats.extent);
                    android_log_info(
                        "RQKuramotoSpatialNative",
                        &format!(
                            "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=render-complete renderPolicy=native-vulkan-wsi-surface-panel framesPresented={} particleCount={} extent={}x{} stereoMode={} perEyeExtent={}x{} packedExtent={}x{} {} elapsedMs={}",
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
                            started.elapsed().as_millis(),
                        ),
                    );
                }
                Err(error) => {
                    android_log_info(
                        "RQKuramotoSpatialNative",
                        &format!(
                            "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=render-failed renderPolicy=native-vulkan-wsi-surface-panel error={}",
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
                ANativeWindow_release(window);
            }
            log_start_receipt(mask, &format!("thread-spawn-{}", error.kind()));
        }
    }
    mask
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_kuramoto_1spatial_KuramotoSpatialActivity_nativeStopSurfaceParticleLayer(
    _env: *mut c_void,
    _thiz: *mut c_void,
) {
    STOP_SURFACE_PARTICLES.store(true, Ordering::Relaxed);
    android_log_info(
        "RQKuramotoSpatialNative",
        "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=stop-requested renderPolicy=native-vulkan-wsi-surface-panel",
    );
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_kuramoto_1spatial_KuramotoSpatialActivity_nativeUpdateSurfaceParticleParameters(
    _env: *mut c_void,
    _thiz: *mut c_void,
    driver0_value01: c_float,
    driver1_value01: c_float,
    point_scale: c_float,
) -> i64 {
    let params = SurfaceParticleParameters {
        driver0_value01: clamp_f32(driver0_value01, 0.0, 1.0),
        driver1_value01: clamp_f32(driver1_value01, 0.0, 1.0),
        point_scale: clamp_f32(point_scale, 0.35, 2.25),
        live_hand_depth_offset_meters: f32::from_bits(
            SURFACE_PARTICLE_LIVE_HAND_DEPTH_OFFSET_BITS.load(Ordering::Relaxed),
        ),
        diagnostic_mode: SURFACE_PARTICLE_DIAGNOSTIC_MODE.load(Ordering::Relaxed),
    };
    SURFACE_PARTICLE_DRIVER0_BITS.store(params.driver0_value01.to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_DRIVER1_BITS.store(params.driver1_value01.to_bits(), Ordering::Relaxed);
    SURFACE_PARTICLE_POINT_SCALE_BITS.store(params.point_scale.to_bits(), Ordering::Relaxed);
    android_log_info(
        "RQKuramotoSpatialNative",
        &format!(
            "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=parameters-updated renderPolicy=native-vulkan-wsi-surface-panel transport=jni-live-queue computeParameterBridge=true liveHandDepthOffsetParameterSource=runtime-hotload-android-property liveHandDepthOffsetProperty={} particleDiagnosticModeProperty={} particleDiagnosticMode={} particleDiagnosticModeName={} driver0Value01={:.3} driver1Value01={:.3} pointScale={:.3} liveHandDepthOffsetMeters={:.3}",
            SURFACE_PARTICLE_LIVE_HAND_DEPTH_OFFSET_PROPERTY,
            SURFACE_PARTICLE_DIAGNOSTIC_MODE_PROPERTY,
            params.diagnostic_mode,
            surface_particle_diagnostic_mode_name(params.diagnostic_mode),
            params.driver0_value01,
            params.driver1_value01,
            params.point_scale,
            params.live_hand_depth_offset_meters,
        ),
    );
    0b111
}

#[no_mangle]
#[allow(non_snake_case)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_kuramoto_1spatial_KuramotoSpatialActivity_nativeUpdateSurfaceParticlePanelPose(
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
        width_meters: finite_or(width_meters, 1.44).clamp(0.20, 4.0),
        height_meters: finite_or(height_meters, 1.44).clamp(0.20, 4.0),
        target_distance_meters: finite_or(target_distance_meters, 0.72).clamp(0.20, 1.50),
        valid: true,
    };
    store_surface_particle_panel_projection(projection);
    store_live_hand_panel_basis(
        projection.center,
        projection.right,
        projection.up,
        projection.target_distance_meters,
        projection.valid,
    );
    let update_count = SURFACE_PARTICLE_PANEL_POSE_LOG_COUNT.fetch_add(1, Ordering::Relaxed);
    if update_count < 4 {
        android_log_info(
            "RQKuramotoSpatialNative",
            &format!(
                "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=panel-pose-updated renderPolicy=native-vulkan-wsi-surface-panel panelPoseNativeUpdate=true worldToPanelProjection=spatial-sdk-panel-plane-basis particleLayerTargetDistanceParameterSource=runtime-hotload-android-property panelCenterM={:.4};{:.4};{:.4} panelRight={:.4};{:.4};{:.4} panelUp={:.4};{:.4};{:.4} panelWidthMeters={:.3} panelHeightMeters={:.3} panelTargetDistanceMeters={:.3}",
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
            ),
        );
    }
    0b1_1111
}

fn log_start_receipt(mask: i64, status: &str) {
    android_log_info(
        "RQKuramotoSpatialNative",
        &format!(
            "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status={} renderPolicy=native-vulkan-wsi-surface-panel startMask={} surfaceNonNull={} nativeWindowObtained={} renderThreadSpawned={}",
            marker_token(status),
            mask,
            bool_token((mask & START_SURFACE_NON_NULL) != 0),
            bool_token((mask & START_NATIVE_WINDOW_OBTAINED) != 0),
            bool_token((mask & START_RENDER_THREAD_SPAWNED) != 0),
        ),
    );
}

struct SurfaceParticleStats {
    frames_presented: u32,
    particle_count: u32,
    extent: vk::Extent2D,
}

#[derive(Clone, Copy)]
struct SurfaceParticleStereoLayout {
    stereo_mode: &'static str,
    eye_count: u32,
    per_eye_extent: vk::Extent2D,
    packed_extent: vk::Extent2D,
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
    pub(crate) driver0_value01: f32,
    pub(crate) driver1_value01: f32,
    pub(crate) point_scale: f32,
    pub(crate) live_hand_depth_offset_meters: f32,
    pub(crate) diagnostic_mode: u32,
}

fn current_surface_particle_parameters() -> SurfaceParticleParameters {
    poll_surface_particle_hotload_properties();
    SurfaceParticleParameters {
        driver0_value01: f32::from_bits(SURFACE_PARTICLE_DRIVER0_BITS.load(Ordering::Relaxed)),
        driver1_value01: f32::from_bits(SURFACE_PARTICLE_DRIVER1_BITS.load(Ordering::Relaxed)),
        point_scale: f32::from_bits(SURFACE_PARTICLE_POINT_SCALE_BITS.load(Ordering::Relaxed)),
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
                "RQKuramotoSpatialNative",
                &format!(
                    "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=hotload-parameters-updated renderPolicy=native-vulkan-wsi-surface-panel transport=android-system-property liveHandDepthOffsetParameterSource=runtime-hotload-android-property liveHandDepthOffsetProperty={} liveHandDepthOffsetMeters={:.3}",
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
                "RQKuramotoSpatialNative",
                &format!(
                    "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=hotload-parameters-updated renderPolicy=native-vulkan-wsi-surface-panel transport=android-system-property particleDiagnosticModeProperty={} particleDiagnosticMode={} particleDiagnosticModeName={}",
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
    SURFACE_PARTICLE_PANEL_POSE_VALID.store(projection.valid, Ordering::Relaxed);
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

fn normalize_or(value: [f32; 3], fallback: [f32; 3]) -> [f32; 3] {
    let len_sq = value[0] * value[0] + value[1] * value[1] + value[2] * value[2];
    if len_sq > 0.0000001 {
        let inv_len = len_sq.sqrt().recip();
        [value[0] * inv_len, value[1] * inv_len, value[2] * inv_len]
    } else {
        fallback
    }
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
    let app_name = CString::new("rusty-quest-kuramoto-spatial").expect("static app name");
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
    let descriptor_set_layout = create_descriptor_set_layout(&device)?;
    let pipeline_layout = create_pipeline_layout(&device, descriptor_set_layout)?;
    let graphics_pipeline = create_graphics_pipeline(&device, render_pass, pipeline_layout)?;
    let compute_pipeline = create_compute_pipeline(&device, pipeline_layout)?;
    let memory_properties = instance.get_physical_device_memory_properties(physical_device);
    let mut replay_hands =
        ReplayHandsRenderer::new(&device, &memory_properties, render_pass, openxr_handles)?;
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
    let descriptor_pool = create_descriptor_pool(&device)?;
    let descriptor_set = create_particle_descriptor_set(
        &device,
        descriptor_pool,
        descriptor_set_layout,
        particle_buffer,
        particle_buffer_size,
    )?;
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

    let start = Instant::now();
    let mut frames_presented = 0_u32;
    let stereo_layout = surface_particle_stereo_layout(extent);
    let initial_params = current_surface_particle_parameters();
    android_log_info(
        "RQKuramotoSpatialNative",
        &format!(
            "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=render-loop-ready renderPolicy=native-vulkan-wsi-surface-panel native-surface-compute-stereo-proof=true sideBySideStereoProof=true stereoDebugMarkers=false computeParticleStateBuffer=true computeShaderDispatchReady=true liveHandDepthOffsetParameterSource=runtime-hotload-android-property liveHandDepthOffsetProperty={} liveHandDepthOffsetMeters={:.3} particleDiagnosticModeProperty={} particleDiagnosticMode={} particleDiagnosticModeName={} particleStorageBufferBytes={} particleCount={} extent={}x{} stereoMode={} perEyeExtent={}x{} packedExtent={}x{} {} {} swapchainImages={}",
            SURFACE_PARTICLE_LIVE_HAND_DEPTH_OFFSET_PROPERTY,
            initial_params.live_hand_depth_offset_meters,
            SURFACE_PARTICLE_DIAGNOSTIC_MODE_PROPERTY,
            initial_params.diagnostic_mode,
            surface_particle_diagnostic_mode_name(initial_params.diagnostic_mode),
            particle_buffer_size,
            particle_count,
            extent.width,
            extent.height,
            stereo_layout.stereo_mode,
            stereo_layout.per_eye_extent.width,
            stereo_layout.per_eye_extent.height,
            stereo_layout.packed_extent.width,
            stereo_layout.packed_extent.height,
            SURFACE_PARTICLE_PROJECTION_MARKERS,
            replay_hands.marker_fields(),
            images.len(),
        ),
    );
    loop {
        if STOP_SURFACE_PARTICLES.load(Ordering::Relaxed) {
            break;
        }
        if max_frames > 0 && frames_presented >= max_frames {
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
        replay_hands.update_live_joints_buffer(&device)?;
        record_command_buffer(
            &device,
            command_buffer,
            render_pass,
            framebuffers[image_index as usize],
            extent,
            graphics_pipeline,
            compute_pipeline,
            pipeline_layout,
            descriptor_set,
            particle_count,
            start.elapsed().as_secs_f32(),
            &mut replay_hands,
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
                "RQKuramotoSpatialNative",
                &format!(
                    "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=first-frame-presented renderPolicy=native-vulkan-wsi-surface-panel native-surface-compute-stereo-proof=true sideBySideStereoProof=true stereoDebugMarkers=false computeParticleStateBuffer=true particleCount={} extent={}x{} stereoMode={} perEyeExtent={}x{} packedExtent={}x{} {} {}",
                    particle_count,
                    extent.width,
                    extent.height,
                    stereo_layout.stereo_mode,
                    stereo_layout.per_eye_extent.width,
                    stereo_layout.per_eye_extent.height,
                    stereo_layout.packed_extent.width,
                    stereo_layout.packed_extent.height,
                    SURFACE_PARTICLE_PROJECTION_MARKERS,
                    replay_hands.marker_fields(),
                ),
            );
        }
    }

    device
        .device_wait_idle()
        .map_err(|error| format!("device-wait-idle-{error:?}"))?;
    replay_hands.destroy(&device);
    device.destroy_fence(frame_fence, None);
    device.destroy_semaphore(render_finished, None);
    device.destroy_semaphore(image_available, None);
    device.destroy_command_pool(command_pool, None);
    device.destroy_descriptor_pool(descriptor_pool, None);
    device.destroy_buffer(particle_buffer, None);
    device.free_memory(particle_buffer_memory, None);
    device.destroy_pipeline(compute_pipeline, None);
    device.destroy_pipeline(graphics_pipeline, None);
    device.destroy_pipeline_layout(pipeline_layout, None);
    device.destroy_descriptor_set_layout(descriptor_set_layout, None);
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

    Ok(SurfaceParticleStats {
        frames_presented,
        particle_count,
        extent,
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
    time_seconds: f32,
    replay_hands: &mut ReplayHandsRenderer,
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
    let workgroup_count = particle_count.saturating_add(SURFACE_PARTICLE_COMPUTE_LOCAL_SIZE - 1)
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
        let frame_counter = (time_seconds * 30.0).max(0.0) as u32;
        replay_hands.record_eye(
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
    }
    device.cmd_end_render_pass(command_buffer);
    device
        .end_command_buffer(command_buffer)
        .map_err(|error| format!("end-command-buffer-{error:?}"))
}
