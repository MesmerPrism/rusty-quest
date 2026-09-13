//! Unit020 JNI source-owner boundary and packed SBS-LR sampling contract.

/// Maps full-eye UV into one half of a packed SBS-LR image while keeping every
/// bilinear footprint on its own side of the seam.
pub(crate) fn packed_sbs_eye_uv(
    eye_index: usize,
    eye_uv: [f32; 2],
    packed_width: u32,
    packed_height: u32,
) -> [f32; 2] {
    let width = packed_width.max(2) as f32;
    let height = packed_height.max(1) as f32;
    let half_width = (packed_width.max(2) / 2).max(1) as f32;
    let inset_x = 0.5 / width;
    let inset_y = 0.5 / height;
    let half_min = if eye_index == 0 { 0.0 } else { 0.5 };
    let half_max = if eye_index == 0 { 0.5 } else { 1.0 };
    let local_x = eye_uv[0].clamp(0.5 / half_width, 1.0 - 0.5 / half_width);
    [
        (half_min + local_x * 0.5).clamp(half_min + inset_x, half_max - inset_x),
        eye_uv[1].clamp(inset_y, 1.0 - inset_y),
    ]
}

#[cfg(target_os = "android")]
mod jni_boundary {
    use std::ffi::c_void;
    use std::sync::{LazyLock, Mutex};

    use jni::{objects::JLongArray, sys::jlongArray, JNIEnv};

    use crate::peer_projection_runtime::{self, SourceReceipt, SOURCE_ABI_WORDS};

    use crate::acamera_sys::{ANativeWindow, ANativeWindow_release};

    #[derive(Clone, Copy)]
    struct BoundSurface {
        route_generation: i64,
        launch_challenge: i64,
        surface_generation: i64,
        window_address: usize,
    }

    static BOUND_SURFACE: LazyLock<Mutex<Option<BoundSurface>>> =
        LazyLock::new(|| Mutex::new(None));

    #[link(name = "android")]
    unsafe extern "C" {
        fn ANativeWindow_fromSurface(env: *mut c_void, surface: *mut c_void) -> *mut c_void;
    }

    unsafe fn acquire_surface_window(
        env: *mut jni::sys::JNIEnv,
        surface: *mut c_void,
    ) -> *mut ANativeWindow {
        if env.is_null() || surface.is_null() {
            return std::ptr::null_mut();
        }
        ANativeWindow_fromSurface(env.cast(), surface).cast()
    }

    unsafe fn replace_bound_surface(binding: Option<BoundSurface>) {
        let mut current = BOUND_SURFACE
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let previous = std::mem::replace(&mut *current, binding);
        if let Some(previous) = previous {
            ANativeWindow_release(previous.window_address as *mut ANativeWindow);
        }
    }

    pub(crate) unsafe fn acquire_exact_bound_surface(
        env: *mut jni::sys::JNIEnv,
        surface: *mut c_void,
        route_generation: i64,
        launch_challenge: i64,
        surface_generation: i64,
    ) -> *mut ANativeWindow {
        let window = acquire_surface_window(env, surface);
        if window.is_null() {
            return window;
        }
        let matches = BOUND_SURFACE
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .is_some_and(|bound| {
                bound.route_generation == route_generation
                    && bound.launch_challenge == launch_challenge
                    && bound.surface_generation == surface_generation
                    && bound.window_address == window as usize
            });
        if !matches {
            ANativeWindow_release(window);
            return std::ptr::null_mut();
        }
        window
    }

    pub(crate) unsafe fn exact_bound_surface_matches(
        env: *mut jni::sys::JNIEnv,
        surface: *mut c_void,
        route_generation: i64,
        launch_challenge: i64,
        surface_generation: i64,
    ) -> bool {
        let window = acquire_exact_bound_surface(
            env,
            surface,
            route_generation,
            launch_challenge,
            surface_generation,
        );
        if window.is_null() {
            return false;
        }
        ANativeWindow_release(window);
        true
    }

    unsafe fn input_words(
        env: &mut JNIEnv<'_>,
        raw: jlongArray,
    ) -> Option<[i64; SOURCE_ABI_WORDS]> {
        if raw.is_null() {
            return None;
        }
        let array = JLongArray::from_raw(raw);
        if env.get_array_length(&array).ok()? as usize != SOURCE_ABI_WORDS {
            return None;
        }
        let mut words = [0_i64; SOURCE_ABI_WORDS];
        env.get_long_array_region(&array, 0, &mut words).ok()?;
        Some(words)
    }

    fn output_words(env: &mut JNIEnv<'_>, receipt: SourceReceipt) -> jlongArray {
        let Ok(array) = env.new_long_array(SOURCE_ABI_WORDS as i32) else {
            return std::ptr::null_mut();
        };
        if env
            .set_long_array_region(&array, 0, &receipt.words)
            .is_err()
        {
            return std::ptr::null_mut();
        }
        array.into_raw()
    }

    #[no_mangle]
    #[allow(non_snake_case)]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeRequestSpatialVideoProjectionSource(
        env: *mut jni::sys::JNIEnv,
        _thiz: *mut c_void,
        surface: *mut c_void,
        request_words: jlongArray,
    ) -> jlongArray {
        let env_raw = env;
        let Ok(mut env) = (unsafe { JNIEnv::from_raw(env_raw) }) else {
            return std::ptr::null_mut();
        };
        let words = unsafe { input_words(&mut env, request_words) };
        let window = unsafe { acquire_surface_window(env_raw, surface) };
        let receipt = words
            .map(|words| peer_projection_runtime::request_source(words, !window.is_null()))
            .unwrap_or_else(|| {
                SourceReceipt::unavailable(0, peer_projection_runtime::REASON_MALFORMED)
            });
        let accepted = words.is_some_and(|words| {
            receipt.words[1] == words[1]
                && receipt.words[2] == words[2]
                && !matches!(
                    receipt.words[11],
                    peer_projection_runtime::RESULT_REJECTED
                        | peer_projection_runtime::RESULT_UNAVAILABLE
                )
        });
        unsafe {
            if accepted && receipt.words[2] != peer_projection_runtime::SOURCE_DISABLED {
                replace_bound_surface(Some(BoundSurface {
                    route_generation: receipt.words[1],
                    launch_challenge: receipt.words[5],
                    surface_generation: receipt.words[6],
                    window_address: window as usize,
                }));
            } else {
                if !window.is_null() {
                    ANativeWindow_release(window);
                }
                if accepted {
                    replace_bound_surface(None);
                }
            }
        }
        if receipt.words[2] == peer_projection_runtime::SOURCE_DISABLED
            && receipt.words[11] == peer_projection_runtime::RESULT_PENDING
        {
            crate::camera_hwb_probe::request_camera_hwb_probe_stop();
            crate::spatial_video_projection_probe::request_spatial_video_projection_probe_stop();
        }
        output_words(&mut env, receipt)
    }

    #[no_mangle]
    #[allow(non_snake_case)]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeReadSpatialVideoProjectionSource(
        env: *mut jni::sys::JNIEnv,
        _thiz: *mut c_void,
        route_generation: i64,
    ) -> jlongArray {
        let Ok(mut env) = (unsafe { JNIEnv::from_raw(env) }) else {
            return std::ptr::null_mut();
        };
        let mut receipt = peer_projection_runtime::read_source(route_generation);
        if receipt.words[2] != peer_projection_runtime::SOURCE_DISABLED
            && !BOUND_SURFACE
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .is_some_and(|bound| {
                    bound.route_generation == receipt.words[1]
                        && bound.launch_challenge == receipt.words[5]
                        && bound.surface_generation == receipt.words[6]
                })
        {
            receipt = peer_projection_runtime::mark_route_lost(
                route_generation,
                peer_projection_runtime::REASON_CARRIER_UNAVAILABLE,
            );
        }
        output_words(&mut env, receipt)
    }

    #[no_mangle]
    #[allow(non_snake_case)]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeRequestSpatialVideoProjectionProducerCleanup(
        env: *mut jni::sys::JNIEnv,
        _thiz: *mut c_void,
        route_generation: i64,
        producer_session: i64,
        producer_epoch: i64,
    ) -> jlongArray {
        let Ok(mut env) = (unsafe { JNIEnv::from_raw(env) }) else {
            return std::ptr::null_mut();
        };
        output_words(
            &mut env,
            peer_projection_runtime::request_producer_cleanup(
                route_generation,
                producer_session,
                producer_epoch,
            ),
        )
    }

    #[no_mangle]
    #[allow(non_snake_case)]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStartSpatialPeerProjectionCommonGraph(
        env: *mut jni::sys::JNIEnv,
        _thiz: *mut c_void,
        route_generation: i64,
        surface: *mut c_void,
        width: i32,
        height: i32,
        frame_count: i32,
        launch_challenge: i64,
        layer_generation: i64,
        _layer_switch_count: i64,
        layer_state_code: i32,
    ) -> i64 {
        if route_generation <= 0
            || launch_challenge <= 0
            || layer_generation <= 0
            || layer_state_code != 1
            || !peer_projection_runtime::pending_route(
                route_generation,
                peer_projection_runtime::SOURCE_PEER,
                launch_challenge,
                layer_generation,
            )
        {
            return 0;
        }
        let window = unsafe {
            acquire_exact_bound_surface(
                env,
                surface,
                route_generation,
                launch_challenge,
                layer_generation,
            )
        };
        if window.is_null() {
            peer_projection_runtime::mark_route_lost(
                route_generation,
                peer_projection_runtime::REASON_CARRIER_UNAVAILABLE,
            );
            return 3;
        }
        // The retained carrier does not yet expose a joined provider-swap command channel.
        // Starting the independent video renderer here would race the camera swapchain and
        // falsely claim the shared public/private graph, so keep the route fail-closed.
        let _ = (width, height, frame_count);
        unsafe { ANativeWindow_release(window) };
        peer_projection_runtime::mark_route_lost(
            route_generation,
            peer_projection_runtime::REASON_PROVIDER_REJECTED,
        );
        0
    }
}

#[cfg(target_os = "android")]
pub(crate) use jni_boundary::exact_bound_surface_matches;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sbs_eye_mapping_is_full_eye_and_seam_safe() {
        let left_min = packed_sbs_eye_uv(0, [0.0, 0.0], 1920, 1080);
        let left_max = packed_sbs_eye_uv(0, [1.0, 1.0], 1920, 1080);
        let right_min = packed_sbs_eye_uv(1, [0.0, 0.0], 1920, 1080);
        let right_max = packed_sbs_eye_uv(1, [1.0, 1.0], 1920, 1080);
        assert!(left_min[0] > 0.0 && left_max[0] < 0.5);
        assert!(right_min[0] > 0.5 && right_max[0] < 1.0);
        assert!(left_min[1] > 0.0 && left_max[1] < 1.0);
        assert!(right_min[1] > 0.0 && right_max[1] < 1.0);
    }

    #[test]
    fn odd_width_still_never_crosses_the_half_domain() {
        assert!(packed_sbs_eye_uv(0, [1.0, 0.5], 1919, 1080)[0] < 0.5);
        assert!(packed_sbs_eye_uv(1, [0.0, 0.5], 1919, 1080)[0] > 0.5);
    }
}
