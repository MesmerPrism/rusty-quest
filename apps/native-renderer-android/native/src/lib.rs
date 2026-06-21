//! Rust-owned NativeActivity entrypoint for the Quest native renderer route.
//!
//! This crate is the low-level Android app owner for the public blur guide
//! path. It keeps the public APK free of app Java and private effect payloads.

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use rusty_quest_native_renderer_contracts::{validate_native_renderer_plan, NativeRendererPlan};

const PLAN_JSON: &str =
    include_str!("../../../../fixtures/native-renderer/native-hwb-blur-sdf-public.plan.json");
const MARKER_PREFIX: &str = "RUSTY_QUEST_NATIVE_RENDERER";

#[cfg(target_os = "android")]
mod acamera_sys;
#[cfg(target_os = "android")]
mod ahardware_buffer_vulkan;
#[cfg(target_os = "android")]
mod android_events;
#[cfg(target_os = "android")]
mod android_hardware_buffer;
#[cfg(target_os = "android")]
mod camera_projection;
mod camera_projection_metadata;
#[cfg(target_os = "android")]
mod display_composite_capture_export;
#[cfg(target_os = "android")]
mod display_composite_feedback;
#[cfg(target_os = "android")]
mod display_composite_native_stream;
mod display_composite_projection_metadata;
mod environment_depth_geometry;
#[cfg(any(test, not(target_os = "android")))]
mod environment_depth_scene_map;
#[cfg(any(test, not(target_os = "android")))]
mod environment_depth_surface_support;
#[cfg(target_os = "android")]
mod gpu_environment_depth_particle_stats;
#[cfg(target_os = "android")]
mod gpu_environment_depth_particles;
#[cfg(target_os = "android")]
mod gpu_hand_anchor_particles;
#[cfg(target_os = "android")]
mod gpu_hand_mesh_visual;
#[cfg(target_os = "android")]
mod gpu_mesh_replay;
#[cfg(target_os = "android")]
mod gpu_private_particles;
#[cfg(target_os = "android")]
mod gpu_sdf_field;
#[cfg(target_os = "android")]
mod gpu_stimulus_volume;
#[cfg(target_os = "android")]
mod guide_blur_graph;
mod hand_mesh_graft;
#[cfg(target_os = "android")]
mod live_hand_compact;
mod manifold_breath_bridge;
mod manifold_pose_publisher;
#[cfg(target_os = "android")]
mod native_camera;
#[cfg(target_os = "android")]
mod native_camera_metadata;
#[cfg(target_os = "android")]
mod native_camera_profiles;
#[cfg(target_os = "android")]
mod native_camera_reader_selection;
mod native_renderer_camera_options;
mod native_renderer_display_composite_options;
mod native_renderer_environment_depth_options;
mod native_renderer_hand_anchor_particle_options;
mod native_renderer_options;
#[cfg(test)]
mod native_renderer_options_tests;
#[cfg(target_os = "android")]
mod native_renderer_panel_bridge;
mod native_renderer_passthrough_style_options;
mod native_renderer_projection_border_stretch_options;
mod native_renderer_properties;
mod native_renderer_property_values;
mod native_renderer_stimulus_panel;
mod native_renderer_stimulus_volume_options;
#[cfg(target_os = "android")]
mod native_renderer_timing;
mod native_renderer_video_projection_options;
mod native_renderer_visual_options;
#[cfg(target_os = "android")]
mod openxr_environment_depth;
#[cfg(target_os = "android")]
mod openxr_passthrough_style;
#[cfg(target_os = "android")]
mod openxr_stimulus_actions;
#[cfg(target_os = "android")]
mod private_extension_slot;
mod projection_rect;
mod projection_target_state;
mod recorded_hand_replay;
#[cfg(target_os = "android")]
mod video_projection;
mod video_projection_metadata;
#[cfg(target_os = "android")]
mod video_projection_native_stream;
#[cfg(target_os = "android")]
mod video_projection_player_bridge;
#[cfg(target_os = "android")]
mod xr_vulkan;

fn load_public_plan() -> Result<NativeRendererPlan, String> {
    let plan: NativeRendererPlan = serde_json::from_str(PLAN_JSON)
        .map_err(|error| format!("plan JSON parse failed: {error}"))?;
    validate_native_renderer_plan(&plan).map_err(|errors| {
        errors
            .iter()
            .map(|error| error.message.as_str())
            .collect::<Vec<_>>()
            .join("; ")
    })?;
    Ok(plan)
}

#[cfg(target_os = "android")]
#[no_mangle]
fn android_on_create(_state: &android_activity::OnCreateState) {
    marker(
        "activity-created",
        "entrypoint=NativeActivity rustNativeActivity=true javaPackaged=true panelActivity=ControlPanelActivity",
    );
    match request_runtime_permissions(_state) {
        Ok(()) => marker(
            "permission-request",
            "status=requested owner=rust-native-jni method=Activity.requestPermissions permissions=CAMERA,HAND_TRACKING,HEADSET_CAMERA,SPATIAL_CAMERA,USE_SCENE,OPENXR,OPENXR_SYSTEM",
        ),
        Err(error) => marker(
            "permission-request",
            format!(
                "status=error owner=rust-native-jni reason={}",
                sanitize(&error)
            ),
        ),
    }
}

#[cfg(target_os = "android")]
fn request_runtime_permissions(state: &android_activity::OnCreateState) -> Result<(), String> {
    use jni::{
        jni_sig, jni_str,
        objects::{JObject, JValue},
        JavaVM,
    };

    let vm = unsafe { JavaVM::from_raw(state.vm_as_ptr().cast()) };
    let activity = state.activity_as_ptr() as jni::sys::jobject;
    vm.attach_current_thread(|env| -> jni::errors::Result<()> {
        let activity = unsafe { env.as_cast_raw::<JObject>(&activity)? };
        let string_class = env.find_class(jni_str!("java/lang/String"))?;
        let permissions = [
            "android.permission.CAMERA",
            "com.oculus.permission.HAND_TRACKING",
            "horizonos.permission.HEADSET_CAMERA",
            "horizonos.permission.SPATIAL_CAMERA",
            "horizonos.permission.USE_SCENE",
            "org.khronos.openxr.permission.OPENXR",
            "org.khronos.openxr.permission.OPENXR_SYSTEM",
        ];
        let permission_array =
            env.new_object_array(permissions.len() as i32, string_class, JObject::null())?;
        for (index, permission) in permissions.iter().enumerate() {
            let permission = env.new_string(*permission)?;
            permission_array.set_element(env, index, JObject::from(permission))?;
        }
        let permission_array = JObject::from(permission_array);
        env.call_method(
            activity,
            jni_str!("requestPermissions"),
            jni_sig!("([Ljava/lang/String;I)V"),
            &[JValue::Object(&permission_array), JValue::Int(7101)],
        )?;
        Ok(())
    })
    .map_err(|error| format!("requestPermissions failed: {error}"))
}

#[cfg(target_os = "android")]
#[no_mangle]
fn android_main(app: android_activity::AndroidApp) {
    let plan = match load_public_plan() {
        Ok(plan) => plan,
        Err(error) => {
            marker(
                "plan-load",
                format!("status=error reason={}", sanitize(&error)),
            );
            keep_activity_alive_after_error(app);
            return;
        }
    };

    marker(
        "plan-loaded",
        format!(
            "status=ok planId={} targetRuntime={} publicEffectLayers=blur-guide,peripheral-stretch-border,video-border-blend privateLayerSlots=abi-only privatePayloads=false cameraLeft={} cameraRight={} finalExternalHwbSamples={} guideTextureSamples={} colorConformanceRequired={}",
            sanitize(&plan.plan_id),
            sanitize(&plan.target_runtime),
            sanitize(&plan.camera_source.camera_ids.left),
            sanitize(&plan.camera_source.camera_ids.right),
            plan.cost_model.external_hwb_samples_per_final_fragment,
            plan.cost_model.guide_texture_samples_per_final_fragment,
            plan.camera_source.hardware_buffer_import.color_conformance_required
        ),
    );

    let runtime_options =
        native_renderer_options::NativeRendererRuntimeOptions::load_from_android_properties();
    let runtime_options =
        native_renderer_stimulus_panel::apply_app_private_candidate(&app, runtime_options);
    display_composite_capture_export::configure(&app, runtime_options.display_composite_settings);
    marker(
        "render-mode",
        format!(
            "status=config property={} renderMode={} customStereoProjectionEnabled={} nativePassthroughRequested={} solidBlackBackground={} openxrDefaultHandVisualRequested={} sdfVisualEnabled={} handMeshGraftCopiesEnabled={} handMeshGraftScaleMultiplier={:.2} realHandsProperty={} handMeshRealHandsVisible={} {}",
            native_renderer_options::PROP_RENDER_MODE,
            runtime_options.render_mode.marker_value(),
            runtime_options.render_mode.uses_custom_stereo_projection(),
            runtime_options.render_mode.uses_native_passthrough(),
            runtime_options.render_mode.uses_solid_black_background(),
            runtime_options
                .render_mode
                .requests_openxr_default_hand_visual(),
            runtime_options.sdf_visual_enabled,
            runtime_options.hand_mesh_graft_copies_enabled,
            runtime_options.hand_mesh_graft_copy_scale,
            native_renderer_options::PROP_HAND_MESH_REAL_HANDS_VISIBLE,
            runtime_options.hand_mesh_real_hands_visible,
            runtime_options
                .projection_border_stretch_settings
                .marker_fields(),
        ),
    );
    if runtime_options.render_mode.uses_stimulus_volume()
        || runtime_options.stimulus_volume_settings.enabled
    {
        marker(
            "stimulus-volume",
            format!(
                "status=config renderMode={} nativePassthroughRequested={} solidBlackBackground={} stimulusVolumeRoute={} {}",
                runtime_options.render_mode.marker_value(),
                runtime_options.render_mode.uses_native_passthrough(),
                runtime_options.render_mode.uses_solid_black_background(),
                runtime_options.render_mode.uses_stimulus_volume(),
                runtime_options.stimulus_volume_settings.marker_fields()
            ),
        );
    }
    marker(
        "environment-depth",
        format!(
            "status=config {}",
            runtime_options.environment_depth_settings.marker_fields()
        ),
    );
    marker(
        "display-composite",
        format!(
            "status=config {}",
            runtime_options.display_composite_settings.marker_fields()
        ),
    );
    marker(
        "video-projection",
        format!(
            "status=config {}",
            runtime_options.video_projection_settings.marker_fields()
        ),
    );
    marker(
        "native-passthrough-style",
        format!(
            "status=config {}",
            runtime_options.passthrough_style_settings.marker_fields()
        ),
    );
    let video_projection_playback = video_projection_player_bridge::start_if_enabled(
        &app,
        &runtime_options.video_projection_settings,
    );

    let xr_vulkan_readiness = xr_vulkan::probe(&app);

    let camera_runtime = if runtime_options.render_mode.uses_custom_stereo_projection()
        && runtime_options.camera_output_mode.camera_import_enabled()
    {
        match native_camera::NativeCameraRuntime::start_from_plan(
            &plan,
            runtime_options.camera_resolution_profile,
            runtime_options.camera_reader_max_images,
            runtime_options.camera_quality_profile,
            runtime_options.camera_sync_mode,
            runtime_options.camera_stereo_pairing_policy,
        ) {
            Ok(runtime) => Some(runtime),
            Err(error) => {
                marker(
                    "camera-runtime",
                    format!(
                        "status=error acquisition=ACameraManager reason={} openxrSubmitReady=false vulkanExternalImportReady=false",
                        sanitize(&error)
                    ),
                );
                None
            }
        }
    } else {
        let skipped_reason = if !runtime_options.camera_output_mode.camera_import_enabled() {
            runtime_options.camera_output_mode.marker_value()
        } else {
            runtime_options.render_mode.marker_value()
        };
        marker(
            "camera-runtime",
            format!(
                "status=skipped reason={} cameraRuntimeMode={} customStereoProjectionEnabled={} cameraFramesRequested=false cameraProjectionReady=false",
                skipped_reason,
                runtime_options.render_mode.camera_runtime_mode(),
                runtime_options.render_mode.uses_custom_stereo_projection(),
            ),
        );
        None
    };

    let active_guide_texture_samples = if runtime_options
        .camera_output_mode
        .guide_projection_enabled()
        && runtime_options.render_mode.uses_custom_stereo_projection()
    {
        1
    } else {
        0
    };

    marker(
        "render-loop",
        format!(
            "status=starting minimal-projection-layer=true recordedHandReplayRequested=true openxrProjectionLayer=runtime-submit renderMode={} customStereoProjectionEnabled={} nativePassthroughRequested={} guideProjectionCoverage={} {} finalExternalHwbSamples=0 guideTextureSamples=1 activeGuideTextureSamples={}",
            runtime_options.render_mode.marker_value(),
            runtime_options.render_mode.uses_custom_stereo_projection(),
            runtime_options.render_mode.uses_native_passthrough(),
            runtime_options
                .projection_border_stretch_settings
                .guide_projection_coverage(),
            xr_vulkan_readiness.marker_fields(),
            active_guide_texture_samples,
        ),
    );

    match xr_vulkan::run_projection_loop(&app, camera_runtime.as_ref(), runtime_options) {
        Ok(()) => marker(
            "render-loop",
            "status=stopped reason=openxr-projection-loop-finished",
        ),
        Err(error) => {
            marker(
                "render-loop",
                format!(
                    "status=projection-error reason={} fallback=counter-loop openxrSubmitReady=false vulkanExternalImportReady=false",
                    sanitize(&error)
                ),
            );
            run_counter_fallback_loop(&app, camera_runtime.as_ref(), &xr_vulkan_readiness);
        }
    }

    drop(camera_runtime);
    drop(video_projection_playback);
    marker("render-loop", "status=stopped");
}

#[cfg(target_os = "android")]
fn run_counter_fallback_loop(
    app: &android_activity::AndroidApp,
    camera_runtime: Option<&native_camera::NativeCameraRuntime>,
    xr_vulkan_readiness: &xr_vulkan::XrVulkanReadiness,
) {
    use std::time::Duration;

    let mut running = true;
    let mut tick_index = 0_u64;
    while running {
        android_events::pump_activity_events(app, Duration::from_millis(16), &mut running);

        tick_index = tick_index.saturating_add(1);
        if tick_index == 1 || tick_index % 60 == 0 {
            if let Some(runtime) = camera_runtime {
                let counters = runtime.counter_snapshot();
                marker(
                    "timing-scorecard",
                    format!(
                        "tick={} camera_frames_acquired={} hardware_buffer_imports={} hardware_buffer_cache_hits={} hardware_buffer_cache_misses={} guide_graph_renders={} guide_graph_cache_hits={} sdf_field_updates={} private_layer_invocations={} xr_frames_submitted={} stale_frames={} releaseRetireCount={} visualAcceptance=false projectionReady=false recordedHandReplayVisible=false gpuMeshPath=native-vulkan-storage-buffer cpuSdfPerFrame=false {}",
                        tick_index,
                        counters.camera_frames_acquired,
                        counters.hardware_buffer_imports,
                        counters.hardware_buffer_cache_hits,
                        counters.hardware_buffer_cache_misses,
                        counters.guide_graph_renders,
                        counters.guide_graph_cache_hits,
                        counters.sdf_field_updates,
                        counters.private_layer_invocations,
                        counters.xr_frames_submitted,
                        counters.stale_frames,
                        counters.release_retire_count,
                        xr_vulkan_readiness.marker_fields()
                    ),
                );
            } else {
                marker(
                    "timing-scorecard",
                    format!(
                        "tick={} camera_frames_acquired=0 hardware_buffer_imports=0 hardware_buffer_cache_hits=0 hardware_buffer_cache_misses=0 guide_graph_renders=0 guide_graph_cache_hits=0 sdf_field_updates=0 private_layer_invocations=0 xr_frames_submitted=0 stale_frames=1 releaseRetireCount=0 visualAcceptance=false projectionReady=false recordedHandReplayVisible=false gpuMeshPath=native-vulkan-storage-buffer cpuSdfPerFrame=false {}",
                        tick_index,
                        xr_vulkan_readiness.marker_fields()
                    ),
                );
            }
        }
    }
}

#[cfg(target_os = "android")]
fn keep_activity_alive_after_error(app: android_activity::AndroidApp) {
    use std::time::Duration;

    let mut running = true;
    while running {
        android_events::pump_activity_events(&app, Duration::from_millis(250), &mut running);
    }
}

#[cfg(target_os = "android")]
pub(crate) fn marker(channel: &str, detail: impl AsRef<str>) {
    android_log(format!(
        "{MARKER_PREFIX} channel={} {}",
        sanitize(channel),
        detail.as_ref()
    ));
}

#[cfg(target_os = "android")]
pub(crate) fn android_log(message: impl AsRef<str>) {
    use std::{ffi::CString, os::raw::c_int};

    let tag = CString::new("RQNativeRenderer").expect("static Android log tag is valid");
    let message = sanitize(message.as_ref());
    if let Ok(message) = CString::new(message) {
        unsafe {
            ndk_sys::__android_log_write(
                ndk_sys::android_LogPriority::ANDROID_LOG_INFO.0 as c_int,
                tag.as_ptr(),
                message.as_ptr(),
            );
        }
    }
}

#[cfg(target_os = "android")]
pub(crate) fn android_log_error(message: impl AsRef<str>) {
    use std::{ffi::CString, os::raw::c_int};

    let tag = CString::new("RQNativeRenderer").expect("static Android log tag is valid");
    let message = sanitize(message.as_ref());
    if let Ok(message) = CString::new(message) {
        unsafe {
            ndk_sys::__android_log_write(
                ndk_sys::android_LogPriority::ANDROID_LOG_ERROR.0 as c_int,
                tag.as_ptr(),
                message.as_ptr(),
            );
        }
    }
}

fn sanitize(value: &str) -> String {
    value
        .replace('\0', "\\0")
        .replace('\r', " ")
        .replace('\n', " ")
        .replace('"', "'")
}

#[cfg(test)]
mod tests {
    use super::load_public_plan;

    #[test]
    fn bundled_public_plan_validates() {
        let plan = load_public_plan().expect("public native plan validates");
        assert_eq!(plan.camera_source.camera_ids.left, "50");
        assert_eq!(plan.camera_source.camera_ids.right, "51");
        assert_eq!(plan.cost_model.external_hwb_samples_per_final_fragment, 0);
        assert_eq!(plan.cost_model.guide_texture_samples_per_final_fragment, 1);
    }
}
