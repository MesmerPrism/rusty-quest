//! Diagnostic exports for MediaProjection display-composite capture boundaries.
//!
//! This module is intentionally not part of the normal high-performance path.
//! It writes CPU-visible MediaProjection frames and GPU readbacks into
//! app-private files for headset evidence runs.

use std::{
    ffi::c_void,
    fs,
    path::{Path, PathBuf},
    ptr,
    sync::{
        atomic::{AtomicBool, AtomicU32, Ordering},
        Mutex,
    },
};

use ash::vk;

use crate::{
    acamera_sys::AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
    display_composite_native_stream::DisplayCompositeFrame,
    native_renderer_display_composite_options::NativeDisplayCompositeSettings,
};

const EXPORT_DIR_NAME: &str = "display-composite-capture";
const MEDIA_RAW_NAME: &str = "media-projection-frame.rgba";
const MEDIA_JSON_NAME: &str = "media-projection-frame.json";
const GPU_RAW_NAME: &str = "gpu-sampled-frame.rgba";
const GPU_JSON_NAME: &str = "gpu-sampled-frame.json";
const GPU_SOURCE_LEVEL_PREFIX: &str = "media-projection-gpu-level";
const FEEDBACK_LEVEL_PREFIX: &str = "feedback-screen-level";
const FEEDBACK_LEVEL_EXPORT_COUNT: u32 = 3;
const MIN_STABLE_RENDER_FRAMES: u64 = 180;
const MIN_STABLE_SOURCE_FRAMES: u64 = 60;
const XR_READY_MARKER: &str =
    "openxrSubmitReady=true,requiredXrLayersReady=true,xrSubmittedFrames>=180";

static EXPORT_DIR: Mutex<Option<PathBuf>> = Mutex::new(None);
static MEDIA_FRAME_EXPORTED: AtomicBool = AtomicBool::new(false);
static GPU_FRAME_EXPORTED: AtomicBool = AtomicBool::new(false);
static FEEDBACK_LEVEL_EXPORTS_RESERVED: AtomicU32 = AtomicU32::new(0);
static FEEDBACK_LEVEL_SOURCE_EXPORTS_WRITTEN: AtomicU32 = AtomicU32::new(0);
static FEEDBACK_LEVEL_SCREEN_EXPORTS_WRITTEN: AtomicU32 = AtomicU32::new(0);

pub(crate) fn configure(
    app: &android_activity::AndroidApp,
    settings: NativeDisplayCompositeSettings,
) {
    MEDIA_FRAME_EXPORTED.store(false, Ordering::Release);
    GPU_FRAME_EXPORTED.store(false, Ordering::Release);
    FEEDBACK_LEVEL_EXPORTS_RESERVED.store(0, Ordering::Release);
    FEEDBACK_LEVEL_SOURCE_EXPORTS_WRITTEN.store(0, Ordering::Release);
    FEEDBACK_LEVEL_SCREEN_EXPORTS_WRITTEN.store(0, Ordering::Release);

    let enabled = settings.capture_export_enabled();
    let data_path = app
        .external_data_path()
        .or_else(|| app.internal_data_path());
    let Some(data_path) = data_path else {
        if enabled {
            crate::marker(
                "display-composite-capture-export",
                "status=unavailable reason=missing-app-data-path",
            );
        }
        if let Ok(mut dir) = EXPORT_DIR.lock() {
            *dir = None;
        }
        return;
    };

    if !enabled {
        if let Ok(mut dir) = EXPORT_DIR.lock() {
            *dir = None;
        }
        return;
    }

    let export_dir = data_path.join(EXPORT_DIR_NAME);
    match fs::create_dir_all(&export_dir) {
        Ok(()) => {
            remove_stale_export_files(&export_dir);
            if let Ok(mut dir) = EXPORT_DIR.lock() {
                *dir = Some(export_dir.clone());
            }
            crate::marker(
                "display-composite-capture-export",
                format!(
                    "status=enabled exportDir={} sourceFrameFile={} gpuFrameFile={} feedbackLevelExportCount={} mediaProjectionCpuReadback=true gpuSampledReadback=true feedbackScreenReadback=true",
                    crate::sanitize(&path_marker(&export_dir)),
                    MEDIA_RAW_NAME,
                    GPU_RAW_NAME,
                    FEEDBACK_LEVEL_EXPORT_COUNT
                ),
            );
        }
        Err(error) => {
            if let Ok(mut dir) = EXPORT_DIR.lock() {
                *dir = None;
            }
            crate::marker(
                "display-composite-capture-export",
                format!(
                    "status=error reason=create-export-dir-failed error={} exportDir={}",
                    crate::sanitize(&error.to_string()),
                    crate::sanitize(&path_marker(&export_dir))
                ),
            );
        }
    }
}

pub(crate) fn export_enabled() -> bool {
    EXPORT_DIR.lock().ok().and_then(|dir| dir.clone()).is_some()
}

pub(crate) fn source_export_done() -> bool {
    MEDIA_FRAME_EXPORTED.load(Ordering::Acquire)
}

pub(crate) fn gpu_export_done() -> bool {
    GPU_FRAME_EXPORTED.load(Ordering::Acquire)
}

pub(crate) fn feedback_level_export_done() -> bool {
    FEEDBACK_LEVEL_EXPORTS_RESERVED.load(Ordering::Acquire) >= FEEDBACK_LEVEL_EXPORT_COUNT
}

pub(crate) fn reserve_feedback_level_export_index() -> Option<u32> {
    let mut current = FEEDBACK_LEVEL_EXPORTS_RESERVED.load(Ordering::Acquire);
    loop {
        if current >= FEEDBACK_LEVEL_EXPORT_COUNT {
            return None;
        }
        match FEEDBACK_LEVEL_EXPORTS_RESERVED.compare_exchange(
            current,
            current + 1,
            Ordering::AcqRel,
            Ordering::Acquire,
        ) {
            Ok(_) => return Some(current),
            Err(observed) => current = observed,
        }
    }
}

pub(crate) fn stable_export_frame_ready(
    frame: &DisplayCompositeFrame,
    render_frame_count: u64,
    xr_ready_marker_active: bool,
) -> bool {
    xr_gpu_upload_ready(render_frame_count, xr_ready_marker_active)
        && media_projection_source_ready(frame)
}

pub(crate) fn xr_gpu_upload_ready(render_frame_count: u64, xr_ready_marker_active: bool) -> bool {
    xr_ready_marker_active && render_frame_count >= MIN_STABLE_RENDER_FRAMES
}

pub(crate) fn media_projection_source_ready(frame: &DisplayCompositeFrame) -> bool {
    frame.frame_index >= MIN_STABLE_SOURCE_FRAMES
}

pub(crate) fn xr_ready_marker_label() -> &'static str {
    XR_READY_MARKER
}

pub(crate) fn try_export_media_projection_frame(
    frame: &DisplayCompositeFrame,
    render_frame_count: u64,
    xr_ready_marker_active: bool,
) {
    if source_export_done() {
        return;
    }
    if !stable_export_frame_ready(frame, render_frame_count, xr_ready_marker_active) {
        return;
    }
    let Some(export_dir) = export_dir() else {
        return;
    };
    match unsafe { copy_ahardware_buffer_rgba(frame) } {
        Ok(bytes) => {
            let raw_path = export_dir.join(MEDIA_RAW_NAME);
            let json_path = export_dir.join(MEDIA_JSON_NAME);
            if let Err(error) = fs::write(&raw_path, &bytes) {
                crate::marker(
                    "display-composite-capture-export",
                    format!(
                        "status=error stage=media-projection-frame reason=write-raw-failed error={}",
                        crate::sanitize(&error.to_string())
                    ),
                );
                return;
            }
            let json = frame_json(
                "media-projection-ahardwarebuffer-lock",
                frame.frame_index,
                frame.import_sequence,
                frame.timestamp_ns,
                frame.descriptor.hardware_buffer_id,
                frame.descriptor.width,
                frame.descriptor.height,
                frame.descriptor.stride,
                frame.descriptor.format,
                frame.descriptor.usage,
                render_frame_count,
                xr_ready_marker_active,
                bytes.len(),
            );
            if let Err(error) = fs::write(&json_path, json) {
                crate::marker(
                    "display-composite-capture-export",
                    format!(
                        "status=error stage=media-projection-frame reason=write-json-failed error={}",
                        crate::sanitize(&error.to_string())
                    ),
                );
                return;
            }
            MEDIA_FRAME_EXPORTED.store(true, Ordering::Release);
            crate::marker(
                "display-composite-capture-export",
                format!(
                    "status=written stage=media-projection-frame rawFile={} metadataFile={} width={} height={} stride={} frameIndex={} importSequence={} bytes={} renderedIntoView=false",
                    MEDIA_RAW_NAME,
                    MEDIA_JSON_NAME,
                    frame.descriptor.width,
                    frame.descriptor.height,
                    frame.descriptor.stride,
                    frame.frame_index,
                    frame.import_sequence,
                    bytes.len()
                ),
            );
        }
        Err(error) => {
            crate::marker(
                "display-composite-capture-export",
                format!(
                    "status=error stage=media-projection-frame reason={}",
                    crate::sanitize(&error)
                ),
            );
        }
    }
}

pub(crate) fn write_gpu_sampled_frame(
    frame_index: u64,
    import_sequence: u64,
    timestamp_ns: i64,
    hardware_buffer_id: u64,
    width: u32,
    height: u32,
    format: vk::Format,
    render_frame_count: u64,
    xr_ready_marker_active: bool,
    bytes: &[u8],
) {
    if gpu_export_done() {
        return;
    }
    let Some(export_dir) = export_dir() else {
        return;
    };
    let raw_path = export_dir.join(GPU_RAW_NAME);
    let json_path = export_dir.join(GPU_JSON_NAME);
    if let Err(error) = fs::write(&raw_path, bytes) {
        crate::marker(
            "display-composite-capture-export",
            format!(
                "status=error stage=gpu-sampled-frame reason=write-raw-failed error={}",
                crate::sanitize(&error.to_string())
            ),
        );
        return;
    }
    let json = format!(
        concat!(
            "{{\n",
            "  \"schema\": \"rusty.quest.display_composite_capture_export.v1\",\n",
            "  \"stage\": \"gpu-sampled-vulkan-readback\",\n",
            "  \"frame_index\": {},\n",
            "  \"import_sequence\": {},\n",
            "  \"timestamp_ns\": {},\n",
            "  \"hardware_buffer_id\": {},\n",
            "  \"width\": {},\n",
            "  \"height\": {},\n",
            "  \"stride_pixels\": {},\n",
            "  \"bytes_per_pixel\": 4,\n",
            "  \"vk_format\": \"{:?}\",\n",
            "  \"byte_order\": \"rgba8888\",\n",
            "  \"raw_file\": \"{}\",\n",
            "  \"render_frame_count\": {},\n",
            "  \"xr_ready_marker_active\": {},\n",
            "  \"stable_frame_gate\": true,\n",
            "  \"xr_ready_marker\": \"{}\",\n",
            "  \"min_stable_render_frames\": {},\n",
            "  \"min_stable_source_frames\": {},\n",
            "  \"rendered_into_view\": false,\n",
            "  \"bytes\": {}\n",
            "}}\n"
        ),
        frame_index,
        import_sequence,
        timestamp_ns,
        hardware_buffer_id,
        width,
        height,
        width,
        format,
        GPU_RAW_NAME,
        render_frame_count,
        xr_ready_marker_active,
        XR_READY_MARKER,
        MIN_STABLE_RENDER_FRAMES,
        MIN_STABLE_SOURCE_FRAMES,
        bytes.len()
    );
    if let Err(error) = fs::write(&json_path, json) {
        crate::marker(
            "display-composite-capture-export",
            format!(
                "status=error stage=gpu-sampled-frame reason=write-json-failed error={}",
                crate::sanitize(&error.to_string())
            ),
        );
        return;
    }
    GPU_FRAME_EXPORTED.store(true, Ordering::Release);
    crate::marker(
        "display-composite-capture-export",
        format!(
            "status=written stage=gpu-sampled-frame rawFile={} metadataFile={} width={} height={} frameIndex={} importSequence={} renderFrame={} xrReadyMarkerActive={} bytes={} renderedIntoView=false stableFrameGate=true",
            GPU_RAW_NAME,
            GPU_JSON_NAME,
            width,
            height,
            frame_index,
            import_sequence,
            render_frame_count,
            xr_ready_marker_active,
            bytes.len()
        ),
    );
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn write_gpu_source_level_frame(
    level_index: u32,
    frame_index: u64,
    import_sequence: u64,
    timestamp_ns: i64,
    hardware_buffer_id: u64,
    width: u32,
    height: u32,
    format: vk::Format,
    render_frame_count: u64,
    xr_ready_marker_active: bool,
    recursive_feedback_seeded: bool,
    bytes: &[u8],
) {
    let Some(export_dir) = export_dir() else {
        return;
    };
    let raw_name = level_raw_name(GPU_SOURCE_LEVEL_PREFIX, level_index);
    let json_name = level_json_name(GPU_SOURCE_LEVEL_PREFIX, level_index);
    let raw_path = export_dir.join(&raw_name);
    let json_path = export_dir.join(&json_name);
    if let Err(error) = fs::write(&raw_path, bytes) {
        crate::marker(
            "display-composite-capture-export",
            format!(
                "status=error stage=media-projection-gpu-level reason=write-raw-failed levelIndex={} error={}",
                level_index,
                crate::sanitize(&error.to_string())
            ),
        );
        return;
    }
    let json = gpu_level_json(
        "media-projection-gpu-sampled-level-readback",
        &raw_name,
        level_index,
        frame_index,
        import_sequence,
        timestamp_ns,
        hardware_buffer_id,
        width,
        height,
        format,
        render_frame_count,
        xr_ready_marker_active,
        false,
        recursive_feedback_seeded,
        -1,
        -1,
        bytes.len(),
    );
    if let Err(error) = fs::write(&json_path, json) {
        crate::marker(
            "display-composite-capture-export",
            format!(
                "status=error stage=media-projection-gpu-level reason=write-json-failed levelIndex={} error={}",
                level_index,
                crate::sanitize(&error.to_string())
            ),
        );
        return;
    }
    FEEDBACK_LEVEL_SOURCE_EXPORTS_WRITTEN.fetch_add(1, Ordering::AcqRel);
    crate::marker(
        "display-composite-capture-export",
        format!(
            "status=written stage=media-projection-gpu-level levelIndex={} rawFile={} metadataFile={} width={} height={} frameIndex={} importSequence={} renderFrame={} xrReadyMarkerActive={} bytes={} renderedIntoView=false stableFrameGate=true",
            level_index,
            raw_name,
            json_name,
            width,
            height,
            frame_index,
            import_sequence,
            render_frame_count,
            xr_ready_marker_active,
            bytes.len()
        ),
    );
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn write_feedback_screen_level_frame(
    level_index: u32,
    frame_index: u64,
    import_sequence: u64,
    timestamp_ns: i64,
    hardware_buffer_id: u64,
    width: u32,
    height: u32,
    format: vk::Format,
    render_frame_count: u64,
    xr_ready_marker_active: bool,
    recursive_feedback_seeded: bool,
    recursive_feedback_write_index: u32,
    recursive_feedback_read_index: i32,
    bytes: &[u8],
) {
    let Some(export_dir) = export_dir() else {
        return;
    };
    let raw_name = level_raw_name(FEEDBACK_LEVEL_PREFIX, level_index);
    let json_name = level_json_name(FEEDBACK_LEVEL_PREFIX, level_index);
    let raw_path = export_dir.join(&raw_name);
    let json_path = export_dir.join(&json_name);
    if let Err(error) = fs::write(&raw_path, bytes) {
        crate::marker(
            "display-composite-capture-export",
            format!(
                "status=error stage=feedback-screen-level reason=write-raw-failed levelIndex={} error={}",
                level_index,
                crate::sanitize(&error.to_string())
            ),
        );
        return;
    }
    let json = gpu_level_json(
        "feedback-screen-bound-vulkan-readback",
        &raw_name,
        level_index,
        frame_index,
        import_sequence,
        timestamp_ns,
        hardware_buffer_id,
        width,
        height,
        format,
        render_frame_count,
        xr_ready_marker_active,
        true,
        recursive_feedback_seeded,
        recursive_feedback_write_index as i32,
        recursive_feedback_read_index,
        bytes.len(),
    );
    if let Err(error) = fs::write(&json_path, json) {
        crate::marker(
            "display-composite-capture-export",
            format!(
                "status=error stage=feedback-screen-level reason=write-json-failed levelIndex={} error={}",
                level_index,
                crate::sanitize(&error.to_string())
            ),
        );
        return;
    }
    FEEDBACK_LEVEL_SCREEN_EXPORTS_WRITTEN.fetch_add(1, Ordering::AcqRel);
    crate::marker(
        "display-composite-capture-export",
        format!(
            "status=written stage=feedback-screen-level levelIndex={} rawFile={} metadataFile={} width={} height={} frameIndex={} importSequence={} renderFrame={} xrReadyMarkerActive={} recursiveFeedbackSeeded={} recursiveFeedbackWriteIndex={} recursiveFeedbackReadIndex={} bytes={} renderedIntoView=true stableFrameGate=true",
            level_index,
            raw_name,
            json_name,
            width,
            height,
            frame_index,
            import_sequence,
            render_frame_count,
            xr_ready_marker_active,
            recursive_feedback_seeded,
            recursive_feedback_write_index,
            recursive_feedback_read_index,
            bytes.len()
        ),
    );
}

fn export_dir() -> Option<PathBuf> {
    EXPORT_DIR.lock().ok().and_then(|dir| dir.clone())
}

fn remove_stale_export_files(export_dir: &Path) {
    for name in [MEDIA_RAW_NAME, MEDIA_JSON_NAME, GPU_RAW_NAME, GPU_JSON_NAME] {
        let _ = fs::remove_file(export_dir.join(name));
    }
    for level_index in 0..FEEDBACK_LEVEL_EXPORT_COUNT {
        for prefix in [GPU_SOURCE_LEVEL_PREFIX, FEEDBACK_LEVEL_PREFIX] {
            let _ = fs::remove_file(export_dir.join(level_raw_name(prefix, level_index)));
            let _ = fs::remove_file(export_dir.join(level_json_name(prefix, level_index)));
        }
    }
}

unsafe fn copy_ahardware_buffer_rgba(frame: &DisplayCompositeFrame) -> Result<Vec<u8>, String> {
    let width = frame.descriptor.width as usize;
    let height = frame.descriptor.height as usize;
    let stride = frame.descriptor.stride as usize;
    if width == 0 || height == 0 || stride < width {
        return Err(format!(
            "invalid-descriptor width={} height={} stride={}",
            frame.descriptor.width, frame.descriptor.height, frame.descriptor.stride
        ));
    }

    let mut address: *mut c_void = ptr::null_mut();
    let lock_result = ndk_sys::AHardwareBuffer_lock(
        frame.hardware_buffer.as_ptr(),
        AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
        -1,
        ptr::null(),
        &mut address,
    );
    if lock_result != 0 || address.is_null() {
        return Err(format!(
            "ahardwarebuffer-lock-failed result={} addressNull={} usage=CPU_READ_OFTEN descriptorUsage={}",
            lock_result,
            address.is_null(),
            frame.descriptor.usage
        ));
    }

    let row_bytes = width
        .checked_mul(4)
        .ok_or_else(|| "row-byte-overflow".to_string())?;
    let stride_bytes = stride
        .checked_mul(4)
        .ok_or_else(|| "stride-byte-overflow".to_string())?;
    let total_bytes = row_bytes
        .checked_mul(height)
        .ok_or_else(|| "image-byte-overflow".to_string())?;
    let mut output = vec![0_u8; total_bytes];
    let source = address.cast::<u8>();
    for row in 0..height {
        let src = source.add(row * stride_bytes);
        let dst = output.as_mut_ptr().add(row * row_bytes);
        ptr::copy_nonoverlapping(src, dst, row_bytes);
    }

    let unlock_result =
        ndk_sys::AHardwareBuffer_unlock(frame.hardware_buffer.as_ptr(), ptr::null_mut());
    if unlock_result != 0 {
        return Err(format!(
            "ahardwarebuffer-unlock-failed result={unlock_result}"
        ));
    }
    Ok(output)
}

fn frame_json(
    stage: &str,
    frame_index: u64,
    import_sequence: u64,
    timestamp_ns: i64,
    hardware_buffer_id: u64,
    width: u32,
    height: u32,
    stride: u32,
    format: u32,
    usage: u64,
    render_frame_count: u64,
    xr_ready_marker_active: bool,
    bytes: usize,
) -> String {
    format!(
        concat!(
            "{{\n",
            "  \"schema\": \"rusty.quest.display_composite_capture_export.v1\",\n",
            "  \"stage\": \"{}\",\n",
            "  \"frame_index\": {},\n",
            "  \"import_sequence\": {},\n",
            "  \"timestamp_ns\": {},\n",
            "  \"hardware_buffer_id\": {},\n",
            "  \"width\": {},\n",
            "  \"height\": {},\n",
            "  \"stride_pixels\": {},\n",
            "  \"bytes_per_pixel\": 4,\n",
            "  \"android_format\": {},\n",
            "  \"android_usage\": {},\n",
            "  \"byte_order\": \"rgba8888\",\n",
            "  \"raw_file\": \"{}\",\n",
            "  \"render_frame_count\": {},\n",
            "  \"xr_ready_marker_active\": {},\n",
            "  \"stable_frame_gate\": true,\n",
            "  \"xr_ready_marker\": \"{}\",\n",
            "  \"min_stable_render_frames\": {},\n",
            "  \"min_stable_source_frames\": {},\n",
            "  \"rendered_into_view\": false,\n",
            "  \"bytes\": {}\n",
            "}}\n"
        ),
        stage,
        frame_index,
        import_sequence,
        timestamp_ns,
        hardware_buffer_id,
        width,
        height,
        stride,
        format,
        usage,
        MEDIA_RAW_NAME,
        render_frame_count,
        xr_ready_marker_active,
        XR_READY_MARKER,
        MIN_STABLE_RENDER_FRAMES,
        MIN_STABLE_SOURCE_FRAMES,
        bytes
    )
}

#[allow(clippy::too_many_arguments)]
fn gpu_level_json(
    stage: &str,
    raw_file: &str,
    level_index: u32,
    frame_index: u64,
    import_sequence: u64,
    timestamp_ns: i64,
    hardware_buffer_id: u64,
    width: u32,
    height: u32,
    format: vk::Format,
    render_frame_count: u64,
    xr_ready_marker_active: bool,
    rendered_into_view: bool,
    recursive_feedback_seeded: bool,
    recursive_feedback_write_index: i32,
    recursive_feedback_read_index: i32,
    bytes: usize,
) -> String {
    format!(
        concat!(
            "{{\n",
            "  \"schema\": \"rusty.quest.display_composite_level_export.v1\",\n",
            "  \"stage\": \"{}\",\n",
            "  \"level_index\": {},\n",
            "  \"level_count\": {},\n",
            "  \"frame_index\": {},\n",
            "  \"import_sequence\": {},\n",
            "  \"timestamp_ns\": {},\n",
            "  \"hardware_buffer_id\": {},\n",
            "  \"width\": {},\n",
            "  \"height\": {},\n",
            "  \"stride_pixels\": {},\n",
            "  \"bytes_per_pixel\": 4,\n",
            "  \"vk_format\": \"{:?}\",\n",
            "  \"byte_order\": \"rgba8888\",\n",
            "  \"raw_file\": \"{}\",\n",
            "  \"render_frame_count\": {},\n",
            "  \"xr_ready_marker_active\": {},\n",
            "  \"stable_frame_gate\": true,\n",
            "  \"xr_ready_marker\": \"{}\",\n",
            "  \"min_stable_render_frames\": {},\n",
            "  \"min_stable_source_frames\": {},\n",
            "  \"rendered_into_view\": {},\n",
            "  \"screen_bound_feedback_texture\": {},\n",
            "  \"recursive_feedback_seeded\": {},\n",
            "  \"recursive_feedback_write_index\": {},\n",
            "  \"recursive_feedback_read_index\": {},\n",
            "  \"level_expectation\": \"{}\",\n",
            "  \"bytes\": {}\n",
            "}}\n"
        ),
        stage,
        level_index,
        FEEDBACK_LEVEL_EXPORT_COUNT,
        frame_index,
        import_sequence,
        timestamp_ns,
        hardware_buffer_id,
        width,
        height,
        width,
        format,
        raw_file,
        render_frame_count,
        xr_ready_marker_active,
        XR_READY_MARKER,
        MIN_STABLE_RENDER_FRAMES,
        MIN_STABLE_SOURCE_FRAMES,
        rendered_into_view,
        rendered_into_view,
        recursive_feedback_seeded,
        recursive_feedback_write_index,
        recursive_feedback_read_index,
        level_expectation(level_index),
        bytes
    )
}

fn level_raw_name(prefix: &str, level_index: u32) -> String {
    format!("{prefix}-{level_index:02}.rgba")
}

fn level_json_name(prefix: &str, level_index: u32) -> String {
    format!("{prefix}-{level_index:02}.json")
}

fn level_expectation(level_index: u32) -> &'static str {
    match level_index {
        0 => "first-captured-source-before-feedback-recapture",
        1 => "first-feedback-recapture-candidate",
        2 => "second-feedback-recapture-candidate",
        _ => "additional-feedback-recapture-candidate",
    }
}

fn path_marker(path: &Path) -> String {
    path.to_string_lossy().replace('\\', "/")
}
