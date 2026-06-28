use std::ffi::{c_void, CStr, CString};
use std::os::raw::c_int;
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

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
use crate::android_hardware_buffer::{
    AndroidHardwareBufferDescriptor, AndroidHardwareBufferHandle,
};
use crate::camera_hwb_marker::log_camera_hwb_marker as log_marker;
use crate::camera_hwb_projection_target::{
    camera_hwb_projection_marker_fields, CAMERA_HWB_LEFT_CAMERA_ID, CAMERA_HWB_RIGHT_CAMERA_ID,
};
use crate::spatial_public_multistack::public_multistack_marker_fields;
use crate::{bool_token, marker_token};

const CAMERA_HWB_PROBE_READER_DEFAULT_WIDTH: i32 = 1280;
const CAMERA_HWB_PROBE_READER_DEFAULT_HEIGHT: i32 = 1280;

#[derive(Clone, Copy)]
pub(crate) enum CameraProbeStreamMode {
    MonoSelectedCamera,
    StereoCamera50_51,
}

impl CameraProbeStreamMode {
    fn is_stereo_camera_50_51(self) -> bool {
        matches!(self, Self::StereoCamera50_51)
    }
}

#[derive(Clone)]
pub(crate) struct CameraProbeFrame {
    pub(crate) side_label: &'static str,
    pub(crate) camera_id: String,
    pub(crate) frame_index: u64,
    pub(crate) hwb_import_sequence: u64,
    pub(crate) hardware_buffer: AndroidHardwareBufferHandle,
    pub(crate) descriptor: AndroidHardwareBufferDescriptor,
    pub(crate) timestamp_ns: i64,
}

pub(crate) struct CameraProbeFrameSet {
    pub(crate) left: CameraProbeFrame,
    pub(crate) right: CameraProbeFrame,
}

impl CameraProbeFrameSet {
    pub(crate) fn pair_delta_ns(&self) -> u64 {
        self.left.timestamp_ns.abs_diff(self.right.timestamp_ns)
    }
}

struct CameraProbeContext {
    alive: AtomicBool,
    side_label: &'static str,
    camera_id: String,
    frame_counter: AtomicU64,
    import_counter: AtomicU64,
    latest_frame: Mutex<Option<CameraProbeFrame>>,
    frame_available: Condvar,
}

pub(crate) struct CameraProbeRuntime {
    manager: *mut ACameraManager,
    left: Option<CameraProbeStream>,
    right: Option<CameraProbeStream>,
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
    pub(crate) unsafe fn start(
        reader_max_images: c_int,
        mode: CameraProbeStreamMode,
    ) -> Result<Self, String> {
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

        if mode.is_stereo_camera_50_51() {
            if !available_ids
                .iter()
                .any(|id| id == CAMERA_HWB_LEFT_CAMERA_ID)
                || !available_ids
                    .iter()
                    .any(|id| id == CAMERA_HWB_RIGHT_CAMERA_ID)
            {
                ACameraManager_delete(manager);
                return Err(format!(
                    "stereo-camera-50-51-unavailable-available-{}",
                    available_ids.join(",")
                ));
            }
            let left = match CameraProbeStream::start(
                manager,
                CAMERA_HWB_LEFT_CAMERA_ID,
                "left",
                reader_max_images,
            ) {
                Ok(stream) => stream,
                Err(error) => {
                    ACameraManager_delete(manager);
                    return Err(format!("left-camera-start-{error}"));
                }
            };
            let right = match CameraProbeStream::start(
                manager,
                CAMERA_HWB_RIGHT_CAMERA_ID,
                "right",
                reader_max_images,
            ) {
                Ok(stream) => stream,
                Err(error) => {
                    drop(left);
                    ACameraManager_delete(manager);
                    return Err(format!("right-camera-start-{error}"));
                }
            };
            log_marker(format!(
                "status=camera-runtime-started availableIds={} leftCameraId={} rightCameraId={} leftSelectedPrivateSize={}x{} rightSelectedPrivateSize={}x{} readerMaxImages={} stereoSource=camera50-51 imageFormat=PRIVATE usage=GPU_SAMPLED_IMAGE {} {}",
                marker_token(&available_ids.join(",")),
                CAMERA_HWB_LEFT_CAMERA_ID,
                CAMERA_HWB_RIGHT_CAMERA_ID,
                left.selected_width(),
                left.selected_height(),
                right.selected_width(),
                right.selected_height(),
                reader_max_images,
                camera_hwb_projection_marker_fields(),
                public_multistack_marker_fields(),
            ));
            return Ok(Self {
                manager,
                left: Some(left),
                right: Some(right),
                available_ids,
            });
        }

        let selected_id = match select_probe_camera_id(&available_ids) {
            Some(id) => id,
            None => {
                ACameraManager_delete(manager);
                return Err(format!(
                    "camera-50-51-unavailable-available-{}",
                    available_ids.join(",")
                ));
            }
        };
        let stream =
            match CameraProbeStream::start(manager, &selected_id, "mono", reader_max_images) {
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
            left: Some(stream),
            right: None,
            available_ids,
        })
    }

    pub(crate) fn wait_for_first_frame(&self, timeout: Duration) -> Option<CameraProbeFrame> {
        self.left
            .as_ref()
            .and_then(|stream| stream.wait_for_first_frame(timeout))
    }

    pub(crate) fn wait_for_left_frame_after(
        &self,
        last_hwb_import_sequence: u64,
        timeout: Duration,
    ) -> Option<CameraProbeFrame> {
        self.left
            .as_ref()
            .and_then(|stream| stream.wait_for_frame_after(last_hwb_import_sequence, timeout))
    }

    pub(crate) fn wait_for_right_frame_after(
        &self,
        last_hwb_import_sequence: u64,
        timeout: Duration,
    ) -> Option<CameraProbeFrame> {
        self.right
            .as_ref()
            .and_then(|stream| stream.wait_for_frame_after(last_hwb_import_sequence, timeout))
    }

    pub(crate) fn wait_for_first_stereo_frame(
        &self,
        timeout: Duration,
    ) -> Option<CameraProbeFrameSet> {
        let started = Instant::now();
        let left = self.wait_for_left_frame_after(0, timeout)?;
        let remaining = timeout
            .checked_sub(started.elapsed())
            .unwrap_or_else(|| Duration::from_millis(1));
        let right = self.wait_for_right_frame_after(0, remaining)?;
        Some(CameraProbeFrameSet { left, right })
    }
}

impl Drop for CameraProbeRuntime {
    fn drop(&mut self) {
        self.right.take();
        self.left.take();
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
        side_label: &'static str,
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
            side_label,
            camera_id: camera_id.to_string(),
            frame_counter: AtomicU64::new(0),
            import_counter: AtomicU64::new(0),
            latest_frame: Mutex::new(None),
            frame_available: Condvar::new(),
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
            "status=camera-stream-started side={} cameraId={} selectedPrivateSize={}x{} readerMaxImages={} repeatingSequenceId={} privateOutputSizes={}",
            side_label,
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
        self.wait_for_frame_after(0, timeout)
    }

    fn wait_for_frame_after(
        &self,
        last_hwb_import_sequence: u64,
        timeout: Duration,
    ) -> Option<CameraProbeFrame> {
        let context = unsafe { &*self.context_raw };
        let mut guard = context.latest_frame.lock().ok()?;
        let deadline = Instant::now() + timeout;
        loop {
            if guard
                .as_ref()
                .is_some_and(|frame| frame.hwb_import_sequence > last_hwb_import_sequence)
            {
                return guard.take();
            }
            let now = Instant::now();
            if now >= deadline {
                return None;
            }
            let wait = deadline.saturating_duration_since(now);
            let wait_result = context.frame_available.wait_timeout(guard, wait).ok()?;
            guard = wait_result.0;
            if wait_result.1.timed_out()
                && !guard
                    .as_ref()
                    .is_some_and(|frame| frame.hwb_import_sequence > last_hwb_import_sequence)
            {
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
        "status=camera-frame-acquired side={} cameraId={} frameIndex={} hardwareBufferId={} timestampNs={} width={} height={} format={} usage=0x{:x} stride={} hwbImportSequence={} imageFormat=PRIVATE usageFlag=GPU_SAMPLED_IMAGE",
        context.side_label,
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

    let mut guard = match context.latest_frame.lock() {
        Ok(guard) => guard,
        Err(_) => {
            AImage_delete(image);
            return;
        }
    };
    *guard = Some(CameraProbeFrame {
        side_label: context.side_label,
        camera_id: context.camera_id.clone(),
        frame_index,
        hwb_import_sequence,
        hardware_buffer,
        descriptor,
        timestamp_ns,
    });
    context.frame_available.notify_all();
    AImage_delete(image);
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
