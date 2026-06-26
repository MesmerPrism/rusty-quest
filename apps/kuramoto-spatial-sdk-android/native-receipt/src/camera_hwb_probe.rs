use std::ffi::{c_void, CStr, CString};
use std::mem;
use std::os::raw::c_int;
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use ash::vk;

use crate::acamera_sys::{
    ACameraCaptureSession, ACameraCaptureSession_captureCallbacks, ACameraCaptureSession_close,
    ACameraCaptureSession_setRepeatingRequest, ACameraCaptureSession_stateCallbacks,
    ACameraCaptureSession_stopRepeating, ACameraDevice, ACameraDevice_StateCallbacks,
    ACameraDevice_close, ACameraDevice_createCaptureRequest, ACameraManager, ACameraManager_create,
    ACameraManager_delete, ACameraManager_deleteCameraIdList,
    ACameraManager_getCameraCharacteristics, ACameraManager_getCameraIdList,
    ACameraManager_openCamera, ACameraMetadata, ACameraMetadataConstEntry, ACameraMetadata_free,
    ACameraMetadata_getConstEntry, ACameraOutputTarget, ACameraOutputTarget_create,
    ACameraOutputTarget_free, ACaptureRequest, ACaptureRequest_addTarget, ACaptureRequest_free,
    ACaptureRequest_removeTarget, ACaptureSessionOutput, ACaptureSessionOutputContainer,
    ACaptureSessionOutputContainer_add, ACaptureSessionOutputContainer_create,
    ACaptureSessionOutputContainer_free, ACaptureSessionOutput_create, ACaptureSessionOutput_free,
    AImage, AImageReader, AImageReader_ImageListener, AImageReader_acquireLatestImage,
    AImageReader_delete, AImageReader_getWindow, AImageReader_newWithUsage,
    AImageReader_setImageListener, AImage_delete, AImage_getHardwareBuffer, AImage_getTimestamp,
    ANativeWindow, ANativeWindow_acquire as ACameraNativeWindow_acquire,
    ANativeWindow_release as ACameraNativeWindow_release,
    ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
    AIMAGE_FORMAT_PRIVATE, TEMPLATE_PREVIEW,
};
use crate::ahardware_buffer_vulkan::{
    create_ahb_sampler_ycbcr_conversion, import_ahb_sampled_image,
    query_ahb_vulkan_import_properties, transition_ahb_sampled_image_to_shader_read,
    AhbVulkanFormatKey, AhbVulkanSampledImage, AhbVulkanSampledImageCreateInfo,
};
use crate::android_hardware_buffer::{
    AndroidHardwareBufferDescriptor, AndroidHardwareBufferHandle,
};
use crate::{android_log_info, bool_token, marker_token};

const CAMERA_HWB_PROBE_CHANNEL: &str = "camera-hwb-spatial-probe";
const CAMERA_HWB_PROBE_READER_DEFAULT_WIDTH: i32 = 1280;
const CAMERA_HWB_PROBE_READER_DEFAULT_HEIGHT: i32 = 1280;
const CAMERA_HWB_PROBE_WAIT_FRAME_MS: u64 = 5000;
const CAMERA_HWB_PROBE_MAX_FRAMES: u32 = 900;

static STOP_CAMERA_HWB_PROBE: AtomicBool = AtomicBool::new(false);

#[link(name = "android")]
extern "C" {
    fn ANativeWindow_fromSurface(env: *mut c_void, surface: *mut c_void) -> *mut vk::ANativeWindow;
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_kuramoto_1spatial_KuramotoSpatialActivity_nativeStartCameraHwbProbe(
    env: *mut c_void,
    _thiz: *mut c_void,
    surface: *mut c_void,
    width: c_int,
    height: c_int,
    frame_count: c_int,
    reader_max_images: c_int,
) -> i64 {
    let mut mask = 1_i64;
    if !surface.is_null() {
        mask |= 1 << 1;
    }
    if surface.is_null() || env.is_null() {
        log_marker(format!(
            "status=start-receipt startStatus=missing-env-or-surface startMask={} surfaceNonNull={} nativeWindowObtained=false renderThreadSpawned=false carrier=scenequadlayer-createAsAndroid-vulkan-wsi runtimeCrash=false",
            mask,
            bool_token(!surface.is_null()),
        ));
        return mask;
    }

    let window = unsafe { ANativeWindow_fromSurface(env, surface) };
    if window.is_null() {
        log_marker(format!(
            "status=start-receipt startStatus=native-window-null startMask={} surfaceNonNull=true nativeWindowObtained=false renderThreadSpawned=false carrier=scenequadlayer-createAsAndroid-vulkan-wsi runtimeCrash=false",
            mask,
        ));
        return mask;
    }
    mask |= 1 << 2;
    STOP_CAMERA_HWB_PROBE.store(false, Ordering::Release);

    let window_addr = window as usize;
    let width = width.max(64) as u32;
    let height = height.max(64) as u32;
    let max_frames = (frame_count.max(1) as u32).min(CAMERA_HWB_PROBE_MAX_FRAMES);
    let reader_max_images = reader_max_images.clamp(3, 12);
    let spawn_result = thread::Builder::new()
        .name("kuramoto-spatial-camera-hwb-probe".to_string())
        .spawn(move || {
            let window = window_addr as *mut vk::ANativeWindow;
            let started = Instant::now();
            let result = std::panic::catch_unwind(|| unsafe {
                render_camera_hwb_probe(window, width, height, max_frames, reader_max_images)
            })
            .unwrap_or_else(|_| Err("panic".to_string()));
            unsafe {
                ACameraNativeWindow_release(window.cast::<ANativeWindow>());
            }
            match result {
                Ok(stats) => {
                    log_marker(format!(
                        "status=complete framesPresented={} requestedFrames={} extent={}x{} cameraId={} frameIndex={} hardwareBufferId={} hwbImportSequence={} carrier=scenequadlayer-createAsAndroid-vulkan-wsi vkGetAhbPropertiesResult=success sampledCameraTexture=true samplerMode={} outputMode=luma-checker privateShaderStack=false morphovisionStack=false elapsedMs={} runtimeCrash=false",
                        stats.frames_presented,
                        max_frames,
                        stats.extent.width,
                        stats.extent.height,
                        marker_token(&stats.camera_id),
                        stats.frame_index,
                        stats.hardware_buffer_id,
                        stats.hwb_import_sequence,
                        stats.sampler_mode,
                        started.elapsed().as_millis(),
                    ));
                }
                Err(error) => {
                    log_marker(format!(
                        "status=render-failed carrier=scenequadlayer-createAsAndroid-vulkan-wsi error={} sampledCameraTexture=false privateShaderStack=false morphovisionStack=false runtimeCrash=false",
                        marker_token(&error),
                    ));
                }
            }
        });

    match spawn_result {
        Ok(_) => {
            mask |= 1 << 3;
            log_marker(format!(
                "status=start-receipt startStatus=started startMask={} surfaceNonNull=true nativeWindowObtained=true renderThreadSpawned=true requestedWidthPx={} requestedHeightPx={} requestedFrames={} readerMaxImages={} carrier=scenequadlayer-createAsAndroid-vulkan-wsi privateShaderStack=false morphovisionStack=false runtimeCrash=false",
                mask, width, height, max_frames, reader_max_images,
            ));
        }
        Err(error) => {
            unsafe {
                ACameraNativeWindow_release(window.cast::<ANativeWindow>());
            }
            log_marker(format!(
                "status=start-receipt startStatus=thread-spawn-{} startMask={} surfaceNonNull=true nativeWindowObtained=true renderThreadSpawned=false carrier=scenequadlayer-createAsAndroid-vulkan-wsi runtimeCrash=false",
                error.kind(),
                mask,
            ));
        }
    }
    mask
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_kuramoto_1spatial_KuramotoSpatialActivity_nativeStopCameraHwbProbe(
    _env: *mut c_void,
    _thiz: *mut c_void,
) {
    STOP_CAMERA_HWB_PROBE.store(true, Ordering::Release);
    log_marker(
        "status=stop-requested carrier=scenequadlayer-createAsAndroid-vulkan-wsi runtimeCrash=false"
            .to_string(),
    );
}

struct CameraHwbProbeStats {
    frames_presented: u32,
    extent: vk::Extent2D,
    camera_id: String,
    frame_index: u64,
    hardware_buffer_id: u64,
    hwb_import_sequence: u64,
    sampler_mode: &'static str,
}

struct CameraProbeFrame {
    camera_id: String,
    frame_index: u64,
    hwb_import_sequence: u64,
    hardware_buffer: AndroidHardwareBufferHandle,
    descriptor: AndroidHardwareBufferDescriptor,
    timestamp_ns: i64,
}

struct CameraProbeContext {
    alive: AtomicBool,
    camera_id: String,
    frame_counter: AtomicU64,
    import_counter: AtomicU64,
    first_frame: Mutex<Option<CameraProbeFrame>>,
    first_frame_available: Condvar,
}

struct CameraProbeRuntime {
    manager: *mut ACameraManager,
    stream: Option<CameraProbeStream>,
    available_ids: Vec<String>,
}

struct CameraProbeStream {
    camera_id: String,
    selected_size: [i32; 2],
    capture_session: *mut ACameraCaptureSession,
    output_container: *mut ACaptureSessionOutputContainer,
    output: *mut ACaptureSessionOutput,
    camera_device: *mut ACameraDevice,
    target: *mut ACameraOutputTarget,
    window: *mut ANativeWindow,
    reader: *mut AImageReader,
    capture_request: *mut ACaptureRequest,
    capture_callbacks: ACameraCaptureSession_captureCallbacks,
    context_raw: *const CameraProbeContext,
}

impl CameraProbeRuntime {
    unsafe fn start(reader_max_images: c_int) -> Result<Self, String> {
        let manager = ACameraManager_create();
        if manager.is_null() {
            return Err("ACameraManager_create-null".to_string());
        }
        let available_ids = match enumerate_camera_ids(manager) {
            Ok(ids) => ids,
            Err(error) => {
                ACameraManager_delete(manager);
                return Err(error);
            }
        };
        let selected_id = select_probe_camera_id(&available_ids).ok_or_else(|| {
            format!(
                "camera-50-51-unavailable-available-{}",
                available_ids.join(",")
            )
        })?;
        let stream = match CameraProbeStream::start(manager, &selected_id, reader_max_images) {
            Ok(stream) => stream,
            Err(error) => {
                ACameraManager_delete(manager);
                return Err(error);
            }
        };
        log_marker(format!(
            "status=camera-runtime-started availableIds={} selectedCameraId={} selectedPrivateSize={}x{} readerMaxImages={} cameraPreference=50-then-51 imageFormat=PRIVATE usage=GPU_SAMPLED_IMAGE",
            marker_token(&available_ids.join(",")),
            marker_token(&selected_id),
            stream.selected_width(),
            stream.selected_height(),
            reader_max_images,
        ));
        Ok(Self {
            manager,
            stream: Some(stream),
            available_ids,
        })
    }

    fn wait_for_first_frame(&self, timeout: Duration) -> Option<CameraProbeFrame> {
        self.stream
            .as_ref()
            .and_then(|stream| stream.wait_for_first_frame(timeout))
    }
}

impl Drop for CameraProbeRuntime {
    fn drop(&mut self) {
        self.stream.take();
        if !self.manager.is_null() {
            unsafe {
                ACameraManager_delete(self.manager);
            }
            self.manager = ptr::null_mut();
        }
        log_marker(format!(
            "status=camera-runtime-stopped availableIds={} runtimeCrash=false",
            marker_token(&self.available_ids.join(",")),
        ));
    }
}

impl CameraProbeStream {
    unsafe fn start(
        manager: *mut ACameraManager,
        camera_id: &str,
        reader_max_images: c_int,
    ) -> Result<Self, String> {
        let camera_id_c = CString::new(camera_id).map_err(|_| "camera-id-nul".to_string())?;
        let private_sizes = load_private_output_sizes(manager, camera_id_c.as_ptr());
        let selected_size = select_private_size(&private_sizes);

        let mut camera_device = ptr::null_mut();
        let mut state_callbacks = ACameraDevice_StateCallbacks {
            context: ptr::null_mut(),
            onDisconnected: Some(camera_device_disconnected),
            onError: Some(camera_device_error),
        };
        let open_status = ACameraManager_openCamera(
            manager,
            camera_id_c.as_ptr(),
            &mut state_callbacks,
            &mut camera_device,
        );
        if open_status != 0 || camera_device.is_null() {
            return Err(format!("ACameraManager_openCamera-status-{open_status}"));
        }

        let mut capture_request = ptr::null_mut();
        if ACameraDevice_createCaptureRequest(camera_device, TEMPLATE_PREVIEW, &mut capture_request)
            != 0
            || capture_request.is_null()
        {
            ACameraDevice_close(camera_device);
            return Err("ACameraDevice_createCaptureRequest-failed".to_string());
        }

        let mut reader = ptr::null_mut();
        let reader_status = AImageReader_newWithUsage(
            selected_size[0],
            selected_size[1],
            AIMAGE_FORMAT_PRIVATE,
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
            reader_max_images,
            &mut reader,
        );
        if reader_status != 0 || reader.is_null() {
            ACaptureRequest_free(capture_request);
            ACameraDevice_close(camera_device);
            return Err(format!(
                "AImageReader_newWithUsage-status-{reader_status}-size-{}x{}",
                selected_size[0], selected_size[1]
            ));
        }

        let context = Arc::new(CameraProbeContext {
            alive: AtomicBool::new(true),
            camera_id: camera_id.to_string(),
            frame_counter: AtomicU64::new(0),
            import_counter: AtomicU64::new(0),
            first_frame: Mutex::new(None),
            first_frame_available: Condvar::new(),
        });
        let context_raw = Arc::into_raw(context);
        let mut listener = AImageReader_ImageListener {
            context: context_raw as *mut c_void,
            onImageAvailable: Some(camera_probe_image_available),
        };
        let _ = AImageReader_setImageListener(reader, &mut listener);

        let mut window = ptr::null_mut();
        if AImageReader_getWindow(reader, &mut window) != 0 || window.is_null() {
            drop(Arc::from_raw(context_raw));
            AImageReader_delete(reader);
            ACaptureRequest_free(capture_request);
            ACameraDevice_close(camera_device);
            return Err("AImageReader_getWindow-failed".to_string());
        }
        ACameraNativeWindow_acquire(window);

        let mut target = ptr::null_mut();
        if ACameraOutputTarget_create(window, &mut target) != 0 || target.is_null() {
            ACameraNativeWindow_release(window);
            drop(Arc::from_raw(context_raw));
            AImageReader_delete(reader);
            ACaptureRequest_free(capture_request);
            ACameraDevice_close(camera_device);
            return Err("ACameraOutputTarget_create-failed".to_string());
        }
        let _ = ACaptureRequest_addTarget(capture_request, target);

        let mut output = ptr::null_mut();
        if ACaptureSessionOutput_create(window, &mut output) != 0 || output.is_null() {
            ACaptureRequest_removeTarget(capture_request, target);
            ACameraOutputTarget_free(target);
            ACameraNativeWindow_release(window);
            drop(Arc::from_raw(context_raw));
            AImageReader_delete(reader);
            ACaptureRequest_free(capture_request);
            ACameraDevice_close(camera_device);
            return Err("ACaptureSessionOutput_create-failed".to_string());
        }

        let mut output_container = ptr::null_mut();
        if ACaptureSessionOutputContainer_create(&mut output_container) != 0
            || output_container.is_null()
        {
            ACaptureSessionOutput_free(output);
            ACaptureRequest_removeTarget(capture_request, target);
            ACameraOutputTarget_free(target);
            ACameraNativeWindow_release(window);
            drop(Arc::from_raw(context_raw));
            AImageReader_delete(reader);
            ACaptureRequest_free(capture_request);
            ACameraDevice_close(camera_device);
            return Err("ACaptureSessionOutputContainer_create-failed".to_string());
        }
        let _ = ACaptureSessionOutputContainer_add(output_container, output);

        let mut capture_session = ptr::null_mut();
        let session_callbacks = ACameraCaptureSession_stateCallbacks {
            context: ptr::null_mut(),
            onClosed: None,
            onReady: None,
            onActive: None,
        };
        let session_status = crate::acamera_sys::ACameraDevice_createCaptureSession(
            camera_device,
            output_container,
            &session_callbacks,
            &mut capture_session,
        );
        if session_status != 0 || capture_session.is_null() {
            ACaptureSessionOutputContainer_free(output_container);
            ACaptureSessionOutput_free(output);
            ACaptureRequest_removeTarget(capture_request, target);
            ACameraOutputTarget_free(target);
            ACameraNativeWindow_release(window);
            drop(Arc::from_raw(context_raw));
            AImageReader_delete(reader);
            ACaptureRequest_free(capture_request);
            ACameraDevice_close(camera_device);
            return Err(format!(
                "ACameraDevice_createCaptureSession-status-{session_status}"
            ));
        }

        let mut capture_callbacks = ACameraCaptureSession_captureCallbacks {
            context: ptr::null_mut(),
            onCaptureStarted: None,
            onCaptureProgressed: None,
            onCaptureCompleted: None,
            onCaptureFailed: None,
            onCaptureSequenceCompleted: None,
            onCaptureSequenceAborted: None,
            onCaptureBufferLost: None,
        };
        let mut request = capture_request;
        let mut sequence_id = 0_i32;
        let repeating_status = ACameraCaptureSession_setRepeatingRequest(
            capture_session,
            &mut capture_callbacks,
            1,
            &mut request,
            &mut sequence_id,
        );
        if repeating_status != 0 {
            ACameraCaptureSession_close(capture_session);
            ACaptureSessionOutputContainer_free(output_container);
            ACaptureSessionOutput_free(output);
            ACaptureRequest_removeTarget(capture_request, target);
            ACameraOutputTarget_free(target);
            ACameraNativeWindow_release(window);
            drop(Arc::from_raw(context_raw));
            AImageReader_delete(reader);
            ACaptureRequest_free(capture_request);
            ACameraDevice_close(camera_device);
            return Err(format!(
                "ACameraCaptureSession_setRepeatingRequest-status-{repeating_status}"
            ));
        }

        log_marker(format!(
            "status=camera-stream-started cameraId={} selectedPrivateSize={}x{} readerMaxImages={} repeatingSequenceId={} privateOutputSizes={}",
            marker_token(camera_id),
            selected_size[0],
            selected_size[1],
            reader_max_images,
            sequence_id,
            marker_token(&private_sizes_marker(&private_sizes)),
        ));

        Ok(Self {
            camera_id: camera_id.to_string(),
            selected_size,
            capture_session,
            output_container,
            output,
            camera_device,
            target,
            window,
            reader,
            capture_request,
            capture_callbacks,
            context_raw,
        })
    }

    fn selected_width(&self) -> i32 {
        self.selected_size[0]
    }

    fn selected_height(&self) -> i32 {
        self.selected_size[1]
    }

    fn wait_for_first_frame(&self, timeout: Duration) -> Option<CameraProbeFrame> {
        let context = unsafe { &*self.context_raw };
        let mut guard = context.first_frame.lock().ok()?;
        let deadline = Instant::now() + timeout;
        loop {
            if guard.is_some() {
                return guard.take();
            }
            let now = Instant::now();
            if now >= deadline {
                return None;
            }
            let wait = deadline.saturating_duration_since(now);
            let wait_result = context
                .first_frame_available
                .wait_timeout(guard, wait)
                .ok()?;
            guard = wait_result.0;
            if wait_result.1.timed_out() && guard.is_none() {
                return None;
            }
        }
    }
}

impl Drop for CameraProbeStream {
    fn drop(&mut self) {
        unsafe {
            if !self.context_raw.is_null() {
                (*self.context_raw).alive.store(false, Ordering::Release);
            }
            if !self.reader.is_null() {
                let mut listener = AImageReader_ImageListener {
                    context: ptr::null_mut(),
                    onImageAvailable: None,
                };
                let _ = AImageReader_setImageListener(self.reader, &mut listener);
            }
            if !self.capture_session.is_null() {
                ACameraCaptureSession_stopRepeating(self.capture_session);
                ACameraCaptureSession_close(self.capture_session);
            }
            if !self.output_container.is_null() {
                ACaptureSessionOutputContainer_free(self.output_container);
            }
            if !self.output.is_null() {
                ACaptureSessionOutput_free(self.output);
            }
            if !self.capture_request.is_null() && !self.target.is_null() {
                ACaptureRequest_removeTarget(self.capture_request, self.target);
            }
            if !self.target.is_null() {
                ACameraOutputTarget_free(self.target);
            }
            if !self.window.is_null() {
                ACameraNativeWindow_release(self.window);
            }
            if !self.reader.is_null() {
                AImageReader_delete(self.reader);
            }
            if !self.capture_request.is_null() {
                ACaptureRequest_free(self.capture_request);
            }
            if !self.camera_device.is_null() {
                ACameraDevice_close(self.camera_device);
            }
            if !self.context_raw.is_null() {
                drop(Arc::from_raw(self.context_raw));
                self.context_raw = ptr::null();
            }
        }
        let _ = &self.capture_callbacks;
        log_marker(format!(
            "status=camera-stream-stopped cameraId={} runtimeCrash=false",
            marker_token(&self.camera_id),
        ));
    }
}

unsafe extern "C" fn camera_device_disconnected(
    _context: *mut c_void,
    _device: *mut ACameraDevice,
) {
    log_marker("status=camera-device-disconnected runtimeCrash=false".to_string());
}

unsafe extern "C" fn camera_device_error(
    _context: *mut c_void,
    _device: *mut ACameraDevice,
    error: c_int,
) {
    log_marker(format!(
        "status=camera-device-error errorCode={} runtimeCrash=false",
        error
    ));
}

unsafe extern "C" fn camera_probe_image_available(context: *mut c_void, reader: *mut AImageReader) {
    if context.is_null() || reader.is_null() {
        return;
    }
    let context = &*(context as *const CameraProbeContext);
    if !context.alive.load(Ordering::Acquire) {
        return;
    }

    let mut image: *mut AImage = ptr::null_mut();
    let acquire_result = AImageReader_acquireLatestImage(reader, &mut image);
    if acquire_result != 0 || image.is_null() {
        log_marker(format!(
            "status=camera-acquire-failed cameraId={} acquireResult={} imageNull={} runtimeCrash=false",
            marker_token(&context.camera_id),
            acquire_result,
            bool_token(image.is_null()),
        ));
        return;
    }

    let mut timestamp_ns = 0_i64;
    let _ = AImage_getTimestamp(image, &mut timestamp_ns);
    let mut hardware_buffer_ptr = ptr::null_mut();
    let hwb_result = AImage_getHardwareBuffer(image, &mut hardware_buffer_ptr);
    if hwb_result != 0 || hardware_buffer_ptr.is_null() {
        AImage_delete(image);
        log_marker(format!(
            "status=camera-hwb-failed cameraId={} hwbResult={} hardwareBufferNull={} runtimeCrash=false",
            marker_token(&context.camera_id),
            hwb_result,
            bool_token(hardware_buffer_ptr.is_null()),
        ));
        return;
    }

    let hardware_buffer = match AndroidHardwareBufferHandle::acquire(hardware_buffer_ptr) {
        Ok(handle) => handle,
        Err(error) => {
            AImage_delete(image);
            log_marker(format!(
                "status=camera-hwb-acquire-failed cameraId={} error={} runtimeCrash=false",
                marker_token(&context.camera_id),
                marker_token(&error),
            ));
            return;
        }
    };
    let descriptor = hardware_buffer.descriptor();
    let frame_index = context.frame_counter.fetch_add(1, Ordering::Relaxed) + 1;
    let hwb_import_sequence = context.import_counter.fetch_add(1, Ordering::Relaxed) + 1;
    log_marker(format!(
        "status=camera-frame-acquired cameraId={} frameIndex={} hardwareBufferId={} timestampNs={} width={} height={} format={} usage=0x{:x} stride={} hwbImportSequence={} imageFormat=PRIVATE usageFlag=GPU_SAMPLED_IMAGE",
        marker_token(&context.camera_id),
        frame_index,
        descriptor.hardware_buffer_id,
        timestamp_ns,
        descriptor.width,
        descriptor.height,
        descriptor.format,
        descriptor.usage,
        descriptor.stride,
        hwb_import_sequence,
    ));

    let mut guard = match context.first_frame.lock() {
        Ok(guard) => guard,
        Err(_) => {
            AImage_delete(image);
            return;
        }
    };
    if guard.is_none() {
        *guard = Some(CameraProbeFrame {
            camera_id: context.camera_id.clone(),
            frame_index,
            hwb_import_sequence,
            hardware_buffer,
            descriptor,
            timestamp_ns,
        });
        context.first_frame_available.notify_all();
    }
    AImage_delete(image);
}

unsafe fn render_camera_hwb_probe(
    window: *mut vk::ANativeWindow,
    requested_width: u32,
    requested_height: u32,
    max_frames: u32,
    reader_max_images: c_int,
) -> Result<CameraHwbProbeStats, String> {
    let entry = ash::Entry::load().map_err(|error| format!("vulkan-loader-{error}"))?;
    let app_name = CString::new("rusty-quest-kuramoto-spatial").expect("static app name");
    let engine_name = CString::new("camera-hwb-spatial-probe").expect("static engine name");
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
    let (physical_device, queue_family_index, extension_status) =
        select_camera_surface_device(&instance, &surface_loader, surface, &physical_devices)
            .ok_or_else(|| {
                surface_loader.destroy_surface(surface, None);
                instance.destroy_instance(None);
                "no-camera-hwb-vulkan-device".to_string()
            })?;

    if !extension_status.external_hwb_extension_ready
        || !extension_status.sampler_ycbcr_extension_ready
        || !extension_status.sampler_ycbcr_feature_ready
    {
        surface_loader.destroy_surface(surface, None);
        instance.destroy_instance(None);
        return Err(format!(
            "vulkan-ahb-prereq-missing-externalHwb-{}-samplerYcbcrExt-{}-samplerYcbcrFeature-{}",
            extension_status.external_hwb_extension_ready,
            extension_status.sampler_ycbcr_extension_ready,
            extension_status.sampler_ycbcr_feature_ready,
        ));
    }

    let queue_priorities = [1.0_f32];
    let queue_info = [vk::DeviceQueueCreateInfo::default()
        .queue_family_index(queue_family_index)
        .queue_priorities(&queue_priorities)];
    let device_extensions = [
        ash::khr::swapchain::NAME.as_ptr(),
        ash::android::external_memory_android_hardware_buffer::NAME.as_ptr(),
        ash::khr::sampler_ycbcr_conversion::NAME.as_ptr(),
    ];
    let mut sampler_ycbcr_enable =
        vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default().sampler_ycbcr_conversion(true);
    let device_info = vk::DeviceCreateInfo::default()
        .queue_create_infos(&queue_info)
        .enabled_extension_names(&device_extensions)
        .push_next(&mut sampler_ycbcr_enable);
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
    let command_buffers = device
        .allocate_command_buffers(
            &vk::CommandBufferAllocateInfo::default()
                .command_pool(command_pool)
                .level(vk::CommandBufferLevel::PRIMARY)
                .command_buffer_count(images.len() as u32),
        )
        .map_err(|error| format!("allocate-command-buffers-{error:?}"))?;
    let semaphore_info = vk::SemaphoreCreateInfo::default();
    let image_available = device
        .create_semaphore(&semaphore_info, None)
        .map_err(|error| format!("create-image-semaphore-{error:?}"))?;
    let render_finished = device
        .create_semaphore(&semaphore_info, None)
        .map_err(|error| format!("create-render-semaphore-{error:?}"))?;
    let frame_fence = device
        .create_fence(
            &vk::FenceCreateInfo::default().flags(vk::FenceCreateFlags::SIGNALED),
            None,
        )
        .map_err(|error| format!("create-frame-fence-{error:?}"))?;

    log_marker(format!(
        "status=render-loop-ready carrier=scenequadlayer-createAsAndroid-vulkan-wsi producerPath=Camera2-AImageReader-AHardwareBuffer-Vulkan-WSI swapchainImages={} extent={}x{} surfaceFormat={:?} presentMode={:?} compositeAlpha={:?} externalHwbExtensionReady={} samplerYcbcrExtensionReady={} samplerYcbcrFeatureReady={} outputMode=luma-checker privateShaderStack=false morphovisionStack=false runtimeCrash=false",
        images.len(),
        extent.width,
        extent.height,
        surface_format.format,
        present_mode,
        composite_alpha,
        extension_status.external_hwb_extension_ready,
        extension_status.sampler_ycbcr_extension_ready,
        extension_status.sampler_ycbcr_feature_ready,
    ));

    let camera_runtime = CameraProbeRuntime::start(reader_max_images)?;
    let frame = camera_runtime
        .wait_for_first_frame(Duration::from_millis(CAMERA_HWB_PROBE_WAIT_FRAME_MS))
        .ok_or_else(|| "first-camera-frame-timeout".to_string())?;

    let memory_properties = instance.get_physical_device_memory_properties(physical_device);
    let ahb_device =
        ash::android::external_memory_android_hardware_buffer::Device::new(&instance, &device);
    let (import_properties, format_props) =
        query_ahb_vulkan_import_properties(&ahb_device, &frame.hardware_buffer)?;
    let format_key = import_properties.format_key;
    log_marker(format!(
        "status=ahb-properties cameraId={} frameIndex={} hardwareBufferId={} hwbImportSequence={} vkGetAhbPropertiesResult=success externalFormat={} vkFormat={:?} allocationSize={} memoryTypeBits=0x{:x} formatFeaturesRaw=0x{:x}",
        marker_token(&frame.camera_id),
        frame.frame_index,
        frame.descriptor.hardware_buffer_id,
        frame.hwb_import_sequence,
        format_key.external_format,
        format_key.format,
        import_properties.allocation_size,
        import_properties.memory_type_bits,
        format_props.format_features.as_raw(),
    ));

    let camera_resources =
        create_camera_hwb_probe_resources(&device, render_pass, format_key, &format_props)?;
    let sampled_image = import_ahb_sampled_image(
        &device,
        &memory_properties,
        &frame.hardware_buffer,
        AhbVulkanSampledImageCreateInfo {
            width: frame.descriptor.width.max(1),
            height: frame.descriptor.height.max(1),
            format_key,
            allocation_size: import_properties.allocation_size,
            memory_type_bits: import_properties.memory_type_bits,
            sampler_ycbcr_conversion: camera_resources.sampler_ycbcr_conversion,
            debug_label: "camera-hwb-spatial-probe",
        },
    )?;
    let descriptor_set = allocate_camera_hwb_probe_descriptor_set(
        &device,
        &camera_resources,
        sampled_image.image_view,
    )?;
    let sampler_mode = if format_key.external_format != 0 {
        "external-format-ycbcr"
    } else {
        "concrete-vk-format"
    };
    log_marker(format!(
        "status=ahb-imported cameraId={} frameIndex={} hardwareBufferId={} hwbImportSequence={} sampledCameraTexture=true samplerMode={} descriptorShape={} outputMode=luma-checker privateShaderStack=false morphovisionStack=false",
        marker_token(&frame.camera_id),
        frame.frame_index,
        frame.descriptor.hardware_buffer_id,
        frame.hwb_import_sequence,
        sampler_mode,
        camera_resources.descriptor_shape,
    ));

    let mut frames_presented = 0_u32;
    while frames_presented < max_frames && !STOP_CAMERA_HWB_PROBE.load(Ordering::Acquire) {
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
        record_camera_hwb_probe_command_buffer(
            &device,
            command_buffer,
            render_pass,
            framebuffers[image_index as usize],
            extent,
            &camera_resources,
            descriptor_set,
            &sampled_image,
            frames_presented == 0,
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
            log_marker(format!(
                "status=first-camera-frame-presented cameraId={} frameIndex={} hardwareBufferId={} hwbImportSequence={} carrier=scenequadlayer-createAsAndroid-vulkan-wsi vkGetAhbPropertiesResult=success sampledCameraTexture=true samplerMode={} outputMode=luma-checker privateShaderStack=false morphovisionStack=false timestampNs={} width={} height={} format={} usage=0x{:x} stride={} noRepeatedRawHwbSampling=true runtimeCrash=false",
                marker_token(&frame.camera_id),
                frame.frame_index,
                frame.descriptor.hardware_buffer_id,
                frame.hwb_import_sequence,
                sampler_mode,
                frame.timestamp_ns,
                frame.descriptor.width,
                frame.descriptor.height,
                frame.descriptor.format,
                frame.descriptor.usage,
                frame.descriptor.stride,
            ));
        }
    }

    device
        .device_wait_idle()
        .map_err(|error| format!("device-wait-idle-{error:?}"))?;
    sampled_image.destroy(&device);
    camera_resources.destroy(&device);
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
    drop(camera_runtime);
    device.destroy_device(None);
    surface_loader.destroy_surface(surface, None);
    instance.destroy_instance(None);

    Ok(CameraHwbProbeStats {
        frames_presented,
        extent,
        camera_id: frame.camera_id,
        frame_index: frame.frame_index,
        hardware_buffer_id: frame.descriptor.hardware_buffer_id,
        hwb_import_sequence: frame.hwb_import_sequence,
        sampler_mode,
    })
}

struct CameraHwbProbeResources {
    sampler_ycbcr_conversion: Option<vk::SamplerYcbcrConversion>,
    sampler: vk::Sampler,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_pool: vk::DescriptorPool,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    descriptor_shape: &'static str,
}

impl CameraHwbProbeResources {
    unsafe fn destroy(self, device: &ash::Device) {
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
        device.destroy_sampler(self.sampler, None);
        if let Some(conversion) = self.sampler_ycbcr_conversion {
            device.destroy_sampler_ycbcr_conversion(conversion, None);
        }
    }
}

unsafe fn create_camera_hwb_probe_resources(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    format_key: AhbVulkanFormatKey,
    format_props: &vk::AndroidHardwareBufferFormatPropertiesANDROID<'_>,
) -> Result<CameraHwbProbeResources, String> {
    let sampler_ycbcr = create_ahb_sampler_ycbcr_conversion(
        device,
        format_key,
        format_props,
        "camera-hwb-spatial-probe",
    )?;
    let sampler_ycbcr_handle = sampler_ycbcr.as_ref().map(|conversion| conversion.handle);
    let sampler_ycbcr_metadata = sampler_ycbcr
        .as_ref()
        .map(|conversion| conversion.metadata.clone());
    let linear_supported = sampler_ycbcr_metadata
        .as_ref()
        .map(|metadata| metadata.sampler_linear_filter_supported)
        .unwrap_or_else(|| {
            format_props
                .format_features
                .contains(vk::FormatFeatureFlags::SAMPLED_IMAGE_FILTER_LINEAR)
        });
    let sampler_filter = sampler_ycbcr_metadata
        .as_ref()
        .map(|metadata| metadata.sampler_filter)
        .unwrap_or(if linear_supported {
            vk::Filter::LINEAR
        } else {
            vk::Filter::NEAREST
        });
    let mut sampler_conversion_info = vk::SamplerYcbcrConversionInfo::default();
    let mut sampler_info = vk::SamplerCreateInfo::default()
        .mag_filter(sampler_filter)
        .min_filter(sampler_filter)
        .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
        .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
        .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
        .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE);
    if let Some(conversion) = sampler_ycbcr_handle {
        sampler_conversion_info = sampler_conversion_info.conversion(conversion);
        sampler_info = sampler_info.push_next(&mut sampler_conversion_info);
    }
    let sampler = match device.create_sampler(&sampler_info, None) {
        Ok(sampler) => sampler,
        Err(error) => {
            if let Some(conversion) = sampler_ycbcr_handle {
                device.destroy_sampler_ycbcr_conversion(conversion, None);
            }
            return Err(format!("create-camera-sampler-{error:?}"));
        }
    };

    let descriptor_uses_immutable_sampler = sampler_ycbcr_handle.is_some();
    let immutable_samplers = [sampler];
    let mut descriptor_binding = vk::DescriptorSetLayoutBinding::default()
        .binding(0)
        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::FRAGMENT);
    if descriptor_uses_immutable_sampler {
        descriptor_binding = descriptor_binding.immutable_samplers(&immutable_samplers);
    }
    let descriptor_bindings = [descriptor_binding];
    let descriptor_set_layout = match device.create_descriptor_set_layout(
        &vk::DescriptorSetLayoutCreateInfo::default().bindings(&descriptor_bindings),
        None,
    ) {
        Ok(layout) => layout,
        Err(error) => {
            device.destroy_sampler(sampler, None);
            if let Some(conversion) = sampler_ycbcr_handle {
                device.destroy_sampler_ycbcr_conversion(conversion, None);
            }
            return Err(format!("create-camera-descriptor-set-layout-{error:?}"));
        }
    };
    let descriptor_pool = match device.create_descriptor_pool(
        &vk::DescriptorPoolCreateInfo::default()
            .pool_sizes(&[vk::DescriptorPoolSize::default()
                .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(1)])
            .max_sets(1),
        None,
    ) {
        Ok(pool) => pool,
        Err(error) => {
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            if let Some(conversion) = sampler_ycbcr_handle {
                device.destroy_sampler_ycbcr_conversion(conversion, None);
            }
            return Err(format!("create-camera-descriptor-pool-{error:?}"));
        }
    };
    let set_layouts = [descriptor_set_layout];
    let pipeline_layout = match device.create_pipeline_layout(
        &vk::PipelineLayoutCreateInfo::default().set_layouts(&set_layouts),
        None,
    ) {
        Ok(layout) => layout,
        Err(error) => {
            device.destroy_descriptor_pool(descriptor_pool, None);
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            if let Some(conversion) = sampler_ycbcr_handle {
                device.destroy_sampler_ycbcr_conversion(conversion, None);
            }
            return Err(format!("create-camera-pipeline-layout-{error:?}"));
        }
    };
    let pipeline = match create_camera_hwb_probe_pipeline(device, render_pass, pipeline_layout) {
        Ok(pipeline) => pipeline,
        Err(error) => {
            device.destroy_pipeline_layout(pipeline_layout, None);
            device.destroy_descriptor_pool(descriptor_pool, None);
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            if let Some(conversion) = sampler_ycbcr_handle {
                device.destroy_sampler_ycbcr_conversion(conversion, None);
            }
            return Err(error);
        }
    };

    let descriptor_shape = if descriptor_uses_immutable_sampler {
        "combined-immutable-sampler-ycbcr-conversion"
    } else {
        "combined-rgba-sampler"
    };
    let ycbcr_fields = sampler_ycbcr_metadata
        .as_ref()
        .map(|metadata| metadata.marker_fields())
        .unwrap_or_else(|| "ahbSamplerYcbcrConversion=false".to_string());
    log_marker(format!(
        "status=probe-resources-created externalFormat={} vkFormat={:?} descriptorShape={} samplerMode={} samplerFilter={:?} samplerLinearFilterSupported={} {} sampledCameraTexture=true outputMode=luma-checker",
        format_key.external_format,
        format_key.format,
        descriptor_shape,
        if format_key.external_format != 0 { "external-format-ycbcr" } else { "concrete-vk-format" },
        sampler_filter,
        linear_supported,
        ycbcr_fields,
    ));
    Ok(CameraHwbProbeResources {
        sampler_ycbcr_conversion: sampler_ycbcr_handle,
        sampler,
        descriptor_set_layout,
        descriptor_pool,
        pipeline_layout,
        pipeline,
        descriptor_shape,
    })
}

unsafe fn allocate_camera_hwb_probe_descriptor_set(
    device: &ash::Device,
    resources: &CameraHwbProbeResources,
    image_view: vk::ImageView,
) -> Result<vk::DescriptorSet, String> {
    let set_layouts = [resources.descriptor_set_layout];
    let descriptor_set = device
        .allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(resources.descriptor_pool)
                .set_layouts(&set_layouts),
        )
        .map_err(|error| format!("allocate-camera-descriptor-set-{error:?}"))?
        .pop()
        .ok_or_else(|| "allocate-camera-descriptor-set-empty".to_string())?;
    let image_info = [vk::DescriptorImageInfo::default()
        .sampler(resources.sampler)
        .image_view(image_view)
        .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
    let writes = [vk::WriteDescriptorSet::default()
        .dst_set(descriptor_set)
        .dst_binding(0)
        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .image_info(&image_info)];
    device.update_descriptor_sets(&writes, &[]);
    Ok(descriptor_set)
}

unsafe fn create_camera_hwb_probe_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let vert_module = create_shader_module(
        device,
        include_bytes!(concat!(env!("OUT_DIR"), "/camera_hwb_probe.vert.spv")),
    )?;
    let frag_module = create_shader_module(
        device,
        include_bytes!(concat!(env!("OUT_DIR"), "/camera_hwb_probe.frag.spv")),
    )?;
    let entry_point = CString::new("main").expect("static shader entry");
    let stages = [
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX)
            .module(vert_module)
            .name(&entry_point),
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT)
            .module(frag_module)
            .name(&entry_point),
    ];
    let vertex_input = vk::PipelineVertexInputStateCreateInfo::default();
    let input_assembly = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);
    let viewport_state = vk::PipelineViewportStateCreateInfo::default()
        .viewport_count(1)
        .scissor_count(1);
    let rasterization = vk::PipelineRasterizationStateCreateInfo::default()
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
        .line_width(1.0);
    let multisample = vk::PipelineMultisampleStateCreateInfo::default()
        .rasterization_samples(vk::SampleCountFlags::TYPE_1);
    let color_blend_attachment = [vk::PipelineColorBlendAttachmentState::default()
        .color_write_mask(
            vk::ColorComponentFlags::R
                | vk::ColorComponentFlags::G
                | vk::ColorComponentFlags::B
                | vk::ColorComponentFlags::A,
        )
        .blend_enable(false)];
    let color_blend =
        vk::PipelineColorBlendStateCreateInfo::default().attachments(&color_blend_attachment);
    let dynamic_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let dynamic_state =
        vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dynamic_states);
    let pipeline_info = [vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&vertex_input)
        .input_assembly_state(&input_assembly)
        .viewport_state(&viewport_state)
        .rasterization_state(&rasterization)
        .multisample_state(&multisample)
        .color_blend_state(&color_blend)
        .dynamic_state(&dynamic_state)
        .layout(pipeline_layout)
        .render_pass(render_pass)
        .subpass(0)];
    let pipeline = device
        .create_graphics_pipelines(vk::PipelineCache::null(), &pipeline_info, None)
        .map_err(|(_, error)| format!("create-camera-pipeline-{error:?}"))?
        .remove(0);
    device.destroy_shader_module(frag_module, None);
    device.destroy_shader_module(vert_module, None);
    Ok(pipeline)
}

unsafe fn record_camera_hwb_probe_command_buffer(
    device: &ash::Device,
    command_buffer: vk::CommandBuffer,
    render_pass: vk::RenderPass,
    framebuffer: vk::Framebuffer,
    extent: vk::Extent2D,
    resources: &CameraHwbProbeResources,
    descriptor_set: vk::DescriptorSet,
    sampled_image: &AhbVulkanSampledImage,
    transition_camera_image: bool,
) -> Result<(), String> {
    device
        .reset_command_buffer(command_buffer, vk::CommandBufferResetFlags::empty())
        .map_err(|error| format!("reset-command-buffer-{error:?}"))?;
    device
        .begin_command_buffer(command_buffer, &vk::CommandBufferBeginInfo::default())
        .map_err(|error| format!("begin-command-buffer-{error:?}"))?;
    if transition_camera_image {
        transition_ahb_sampled_image_to_shader_read(device, command_buffer, sampled_image.image);
    }
    let clear_values = [vk::ClearValue {
        color: vk::ClearColorValue {
            float32: [0.0, 0.0, 0.0, 1.0],
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
    let viewport = [vk::Viewport {
        x: 0.0,
        y: 0.0,
        width: extent.width as f32,
        height: extent.height as f32,
        min_depth: 0.0,
        max_depth: 1.0,
    }];
    let scissor = [vk::Rect2D {
        offset: vk::Offset2D { x: 0, y: 0 },
        extent,
    }];
    device.cmd_set_viewport(command_buffer, 0, &viewport);
    device.cmd_set_scissor(command_buffer, 0, &scissor);
    device.cmd_bind_pipeline(
        command_buffer,
        vk::PipelineBindPoint::GRAPHICS,
        resources.pipeline,
    );
    device.cmd_bind_descriptor_sets(
        command_buffer,
        vk::PipelineBindPoint::GRAPHICS,
        resources.pipeline_layout,
        0,
        &[descriptor_set],
        &[],
    );
    device.cmd_draw(command_buffer, 3, 1, 0, 0);
    device.cmd_end_render_pass(command_buffer);
    device
        .end_command_buffer(command_buffer)
        .map_err(|error| format!("end-command-buffer-{error:?}"))?;
    Ok(())
}

unsafe fn create_shader_module(
    device: &ash::Device,
    bytes: &[u8],
) -> Result<vk::ShaderModule, String> {
    if bytes.len() % mem::size_of::<u32>() != 0 {
        return Err("shader-bytes-not-u32-aligned".to_string());
    }
    let code = std::slice::from_raw_parts(
        bytes.as_ptr().cast::<u32>(),
        bytes.len() / mem::size_of::<u32>(),
    );
    device
        .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(code), None)
        .map_err(|error| format!("create-shader-module-{error:?}"))
}

#[derive(Clone, Copy)]
struct CameraVulkanExtensionStatus {
    external_hwb_extension_ready: bool,
    sampler_ycbcr_extension_ready: bool,
    sampler_ycbcr_feature_ready: bool,
}

unsafe fn select_camera_surface_device(
    instance: &ash::Instance,
    surface_loader: &ash::khr::surface::Instance,
    surface: vk::SurfaceKHR,
    physical_devices: &[vk::PhysicalDevice],
) -> Option<(vk::PhysicalDevice, u32, CameraVulkanExtensionStatus)> {
    for physical_device in physical_devices {
        let external_hwb_extension_ready = physical_device_supports_extension(
            instance,
            *physical_device,
            ash::android::external_memory_android_hardware_buffer::NAME,
        );
        let sampler_ycbcr_extension_ready = physical_device_supports_extension(
            instance,
            *physical_device,
            ash::khr::sampler_ycbcr_conversion::NAME,
        );
        let mut sampler_ycbcr_features =
            vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default();
        let mut feature_query =
            vk::PhysicalDeviceFeatures2::default().push_next(&mut sampler_ycbcr_features);
        instance.get_physical_device_features2(*physical_device, &mut feature_query);
        let status = CameraVulkanExtensionStatus {
            external_hwb_extension_ready,
            sampler_ycbcr_extension_ready,
            sampler_ycbcr_feature_ready: sampler_ycbcr_features.sampler_ycbcr_conversion
                == vk::TRUE,
        };
        let queue_family_properties =
            instance.get_physical_device_queue_family_properties(*physical_device);
        for (index, family) in queue_family_properties.iter().enumerate() {
            let present_supported = surface_loader
                .get_physical_device_surface_support(*physical_device, index as u32, surface)
                .unwrap_or(false);
            if family.queue_flags.contains(vk::QueueFlags::GRAPHICS) && present_supported {
                return Some((*physical_device, index as u32, status));
            }
        }
    }
    None
}

unsafe fn physical_device_supports_extension(
    instance: &ash::Instance,
    physical_device: vk::PhysicalDevice,
    extension_name: &'static CStr,
) -> bool {
    instance
        .enumerate_device_extension_properties(physical_device)
        .map(|extensions| {
            extensions.iter().any(|extension| {
                let name = CStr::from_ptr(extension.extension_name.as_ptr());
                name == extension_name
            })
        })
        .unwrap_or(false)
}

fn choose_surface_format(formats: &[vk::SurfaceFormatKHR]) -> vk::SurfaceFormatKHR {
    formats
        .iter()
        .copied()
        .find(|format| {
            format.format == vk::Format::R8G8B8A8_UNORM
                && format.color_space == vk::ColorSpaceKHR::SRGB_NONLINEAR
        })
        .unwrap_or_else(|| {
            formats.first().copied().unwrap_or(vk::SurfaceFormatKHR {
                format: vk::Format::R8G8B8A8_UNORM,
                color_space: vk::ColorSpaceKHR::SRGB_NONLINEAR,
            })
        })
}

fn choose_present_mode(present_modes: &[vk::PresentModeKHR]) -> vk::PresentModeKHR {
    if present_modes.contains(&vk::PresentModeKHR::FIFO) {
        vk::PresentModeKHR::FIFO
    } else {
        present_modes
            .first()
            .copied()
            .unwrap_or(vk::PresentModeKHR::FIFO)
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
    let min = capabilities.min_image_extent;
    let max = capabilities.max_image_extent;
    vk::Extent2D {
        width: requested_width.clamp(min.width.max(1), max.width.max(min.width.max(1))),
        height: requested_height.clamp(min.height.max(1), max.height.max(min.height.max(1))),
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

fn choose_composite_alpha(flags: vk::CompositeAlphaFlagsKHR) -> vk::CompositeAlphaFlagsKHR {
    for candidate in [
        vk::CompositeAlphaFlagsKHR::INHERIT,
        vk::CompositeAlphaFlagsKHR::OPAQUE,
        vk::CompositeAlphaFlagsKHR::PRE_MULTIPLIED,
        vk::CompositeAlphaFlagsKHR::POST_MULTIPLIED,
    ] {
        if flags.contains(candidate) {
            return candidate;
        }
    }
    vk::CompositeAlphaFlagsKHR::OPAQUE
}

unsafe fn create_image_views(
    device: &ash::Device,
    format: vk::Format,
    images: &[vk::Image],
) -> Result<Vec<vk::ImageView>, String> {
    images
        .iter()
        .map(|image| {
            device
                .create_image_view(
                    &vk::ImageViewCreateInfo::default()
                        .image(*image)
                        .view_type(vk::ImageViewType::TYPE_2D)
                        .format(format)
                        .subresource_range(vk::ImageSubresourceRange {
                            aspect_mask: vk::ImageAspectFlags::COLOR,
                            base_mip_level: 0,
                            level_count: 1,
                            base_array_layer: 0,
                            layer_count: 1,
                        }),
                    None,
                )
                .map_err(|error| format!("create-image-view-{error:?}"))
        })
        .collect()
}

unsafe fn create_render_pass(
    device: &ash::Device,
    format: vk::Format,
) -> Result<vk::RenderPass, String> {
    let color_attachment = [vk::AttachmentDescription::default()
        .format(format)
        .samples(vk::SampleCountFlags::TYPE_1)
        .load_op(vk::AttachmentLoadOp::CLEAR)
        .store_op(vk::AttachmentStoreOp::STORE)
        .initial_layout(vk::ImageLayout::UNDEFINED)
        .final_layout(vk::ImageLayout::PRESENT_SRC_KHR)];
    let color_attachment_ref = [vk::AttachmentReference::default()
        .attachment(0)
        .layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL)];
    let subpass = [vk::SubpassDescription::default()
        .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
        .color_attachments(&color_attachment_ref)];
    let dependency = [vk::SubpassDependency::default()
        .src_subpass(vk::SUBPASS_EXTERNAL)
        .dst_subpass(0)
        .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
        .dst_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
        .dst_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)];
    device
        .create_render_pass(
            &vk::RenderPassCreateInfo::default()
                .attachments(&color_attachment)
                .subpasses(&subpass)
                .dependencies(&dependency),
            None,
        )
        .map_err(|error| format!("create-render-pass-{error:?}"))
}

unsafe fn create_framebuffers(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    extent: vk::Extent2D,
    image_views: &[vk::ImageView],
) -> Result<Vec<vk::Framebuffer>, String> {
    image_views
        .iter()
        .map(|view| {
            device
                .create_framebuffer(
                    &vk::FramebufferCreateInfo::default()
                        .render_pass(render_pass)
                        .attachments(&[*view])
                        .width(extent.width)
                        .height(extent.height)
                        .layers(1),
                    None,
                )
                .map_err(|error| format!("create-framebuffer-{error:?}"))
        })
        .collect()
}

unsafe fn enumerate_camera_ids(manager: *mut ACameraManager) -> Result<Vec<String>, String> {
    let mut camera_ids_ptr = ptr::null_mut();
    if ACameraManager_getCameraIdList(manager, &mut camera_ids_ptr) != 0 || camera_ids_ptr.is_null()
    {
        return Err("ACameraManager_getCameraIdList-failed".to_string());
    }
    let camera_ids = &*camera_ids_ptr;
    let mut ids = Vec::new();
    for index in 0..camera_ids.numCameras {
        let id_ptr = *camera_ids.cameraIds.add(index as usize);
        if !id_ptr.is_null() {
            ids.push(CStr::from_ptr(id_ptr).to_string_lossy().into_owned());
        }
    }
    ACameraManager_deleteCameraIdList(camera_ids_ptr);
    Ok(ids)
}

fn select_probe_camera_id(available_ids: &[String]) -> Option<String> {
    if available_ids.iter().any(|id| id == "50") {
        Some("50".to_string())
    } else if available_ids.iter().any(|id| id == "51") {
        Some("51".to_string())
    } else {
        None
    }
}

unsafe fn load_private_output_sizes(
    manager: *mut ACameraManager,
    camera_id: *const std::os::raw::c_char,
) -> Vec<[i32; 2]> {
    let mut metadata: *mut ACameraMetadata = ptr::null_mut();
    if ACameraManager_getCameraCharacteristics(manager, camera_id, &mut metadata) != 0
        || metadata.is_null()
    {
        return Vec::new();
    }
    let stream_configs = read_i32_values(metadata, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS);
    let mut sizes = Vec::new();
    for config in stream_configs.chunks_exact(4) {
        let format = config[0];
        let width = config[1];
        let height = config[2];
        let input = config[3];
        let size = [width, height];
        if format == AIMAGE_FORMAT_PRIVATE as i32 && input == 0 && !sizes.contains(&size) {
            sizes.push(size);
        }
    }
    ACameraMetadata_free(metadata);
    sizes
}

fn select_private_size(sizes: &[[i32; 2]]) -> [i32; 2] {
    if sizes.contains(&[
        CAMERA_HWB_PROBE_READER_DEFAULT_WIDTH,
        CAMERA_HWB_PROBE_READER_DEFAULT_HEIGHT,
    ]) {
        return [
            CAMERA_HWB_PROBE_READER_DEFAULT_WIDTH,
            CAMERA_HWB_PROBE_READER_DEFAULT_HEIGHT,
        ];
    }
    if sizes.contains(&[1280, 960]) {
        return [1280, 960];
    }
    sizes
        .iter()
        .copied()
        .min_by_key(|size| {
            let area = size[0].max(1) as i64 * size[1].max(1) as i64;
            (
                aspect_error_milli([1280, 1280], *size),
                (area - 1280_i64 * 1280_i64).abs(),
            )
        })
        .unwrap_or([
            CAMERA_HWB_PROBE_READER_DEFAULT_WIDTH,
            CAMERA_HWB_PROBE_READER_DEFAULT_HEIGHT,
        ])
}

fn aspect_error_milli(requested: [i32; 2], candidate: [i32; 2]) -> i64 {
    ((requested[0] as i64 * candidate[1] as i64 - candidate[0] as i64 * requested[1] as i64).abs()
        * 1000)
        / (requested[1].max(1) as i64 * candidate[1].max(1) as i64)
}

fn private_sizes_marker(sizes: &[[i32; 2]]) -> String {
    sizes
        .iter()
        .take(12)
        .map(|size| format!("{}x{}", size[0], size[1]))
        .collect::<Vec<_>>()
        .join(";")
}

unsafe fn read_i32_values(metadata: *const ACameraMetadata, tag: u32) -> Vec<i32> {
    let Some(entry) = metadata_entry(metadata, tag) else {
        return Vec::new();
    };
    if entry.count == 0 || entry.data.i32_.is_null() {
        return Vec::new();
    }
    std::slice::from_raw_parts(entry.data.i32_, entry.count as usize).to_vec()
}

unsafe fn metadata_entry(
    metadata: *const ACameraMetadata,
    tag: u32,
) -> Option<ACameraMetadataConstEntry> {
    let mut entry = std::mem::MaybeUninit::<ACameraMetadataConstEntry>::zeroed();
    if ACameraMetadata_getConstEntry(metadata, tag, entry.as_mut_ptr()) == 0 {
        Some(entry.assume_init())
    } else {
        None
    }
}

fn log_marker(fields: String) {
    android_log_info(
        "RQKuramotoSpatialNative",
        &format!(
            "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel={} {}",
            CAMERA_HWB_PROBE_CHANNEL, fields
        ),
    );
}
