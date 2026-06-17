//! Native Android Camera2/HWB runtime for the public blur renderer scaffold.

use std::{
    ffi::{CStr, CString},
    os::raw::c_void,
    ptr,
    sync::{
        atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering},
        Arc, Mutex,
    },
};

use rusty_quest_native_renderer_contracts::NativeRendererPlan;

use crate::{
    acamera_sys::{
        ACameraCaptureSession, ACameraCaptureSession_close,
        ACameraCaptureSession_setRepeatingRequest, ACameraCaptureSession_stateCallbacks,
        ACameraCaptureSession_stopRepeating, ACameraDevice, ACameraDevice_StateCallbacks,
        ACameraDevice_close, ACameraDevice_createCaptureRequest,
        ACameraDevice_createCaptureSession, ACameraManager, ACameraManager_create,
        ACameraManager_delete, ACameraManager_deleteCameraIdList, ACameraManager_getCameraIdList,
        ACameraManager_openCamera, ACameraOutputTarget, ACameraOutputTarget_create,
        ACameraOutputTarget_free, ACameraWindowType, ACaptureRequest, ACaptureRequest_addTarget,
        ACaptureRequest_free, ACaptureRequest_removeTarget, ACaptureSessionOutput,
        ACaptureSessionOutputContainer, ACaptureSessionOutputContainer_add,
        ACaptureSessionOutputContainer_create, ACaptureSessionOutputContainer_free,
        ACaptureSessionOutput_create, ACaptureSessionOutput_free, AImage, AImageReader,
        AImageReader_ImageListener, AImageReader_acquireLatestImage, AImageReader_delete,
        AImageReader_getWindow, AImageReader_newWithUsage, AImageReader_setImageListener,
        AImage_delete, AImage_getHardwareBuffer, AImage_getTimestamp, ANativeWindow_acquire,
        ANativeWindow_release, AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, AIMAGE_FORMAT_PRIVATE,
        TEMPLATE_PREVIEW,
    },
    android_log_error, marker,
};

const READER_WIDTH: i32 = 1280;
const READER_HEIGHT: i32 = 1280;
const READER_MAX_IMAGES: i32 = 4;

pub(crate) struct NativeCameraRuntime {
    manager: *mut ACameraManager,
    counters: Arc<NativeCameraCounters>,
    left: Option<NativeCameraStream>,
    right: Option<NativeCameraStream>,
}

impl NativeCameraRuntime {
    pub(crate) fn start_from_plan(plan: &NativeRendererPlan) -> Result<Self, String> {
        let manager = unsafe { ACameraManager_create() };
        if manager.is_null() {
            return Err("ACameraManager_create returned null".to_string());
        }

        let available_ids = match unsafe { enumerate_camera_ids(manager) } {
            Ok(ids) => ids,
            Err(error) => {
                unsafe {
                    ACameraManager_delete(manager);
                }
                return Err(error);
            }
        };
        marker(
            "camera-discovery",
            format!(
                "status=ok availableIds={} leftRequested={} rightRequested={} leftAvailable={} rightAvailable={}",
                available_ids.join(","),
                plan.camera_source.camera_ids.left,
                plan.camera_source.camera_ids.right,
                available_ids.contains(&plan.camera_source.camera_ids.left),
                available_ids.contains(&plan.camera_source.camera_ids.right)
            ),
        );

        let counters = Arc::new(NativeCameraCounters::default());
        let alive = Arc::new(AtomicBool::new(true));
        let mut runtime = Self {
            manager,
            counters: Arc::clone(&counters),
            left: None,
            right: None,
        };

        runtime.left = Some(unsafe {
            NativeCameraStream::start(
                manager,
                CameraSide::Left,
                &plan.camera_source.camera_ids.left,
                Arc::clone(&counters),
                Arc::clone(&alive),
            )?
        });
        runtime.right = Some(unsafe {
            NativeCameraStream::start(
                manager,
                CameraSide::Right,
                &plan.camera_source.camera_ids.right,
                Arc::clone(&counters),
                alive,
            )?
        });

        marker(
            "camera-runtime",
            "status=started acquisition=ACameraManager imageFormat=PRIVATE usage=GPU_SAMPLED_IMAGE textureUpdateCadence=on-camera-frame sourceFrame=ndk-callback cameraIds=50,51",
        );
        Ok(runtime)
    }

    pub(crate) fn counter_snapshot(&self) -> NativeCounterSnapshot {
        self.counters.snapshot()
    }

    pub(crate) fn latest_stereo_frame(&self) -> Option<NativeStereoCameraFrame> {
        self.counters.latest_stereo_frame()
    }

    pub(crate) fn record_hardware_buffer_cache_hit(&self) {
        self.counters
            .hardware_buffer_cache_hits
            .fetch_add(1, Ordering::Relaxed);
    }

    pub(crate) fn record_hardware_buffer_cache_miss(&self) {
        self.counters
            .hardware_buffer_cache_misses
            .fetch_add(1, Ordering::Relaxed);
    }

    pub(crate) fn record_xr_frame_submitted(&self) {
        self.counters
            .xr_frames_submitted
            .fetch_add(1, Ordering::Relaxed);
    }

    pub(crate) fn record_sdf_field_update(&self) {
        self.counters
            .sdf_field_updates
            .fetch_add(1, Ordering::Relaxed);
    }

    pub(crate) fn record_guide_graph_render(&self) {
        self.counters
            .guide_graph_renders
            .fetch_add(1, Ordering::Relaxed);
    }

    pub(crate) fn record_guide_graph_cache_hit(&self) {
        self.counters
            .guide_graph_cache_hits
            .fetch_add(1, Ordering::Relaxed);
    }

    pub(crate) fn record_private_layer_invocation(&self) {
        self.counters
            .private_layer_invocations
            .fetch_add(1, Ordering::Relaxed);
    }
}

impl Drop for NativeCameraRuntime {
    fn drop(&mut self) {
        self.left.take();
        self.right.take();
        if !self.manager.is_null() {
            unsafe {
                ACameraManager_delete(self.manager);
            }
            self.manager = ptr::null_mut();
        }
        marker("camera-runtime", "status=stopped releaseRetireCount=final");
    }
}

#[derive(Clone)]
pub(crate) struct NativeCameraFrame {
    pub(crate) side: &'static str,
    pub(crate) camera_id: String,
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) timestamp_ns: i64,
    pub(crate) source_frame: u64,
    pub(crate) import_sequence: u64,
    pub(crate) hardware_buffer_id: u64,
    pub(crate) native_format: u32,
    pub(crate) usage: u64,
    pub(crate) layers: u32,
    pub(crate) stride: u32,
    pub(crate) hardware_buffer: AndroidHardwareBufferHandle,
}

#[derive(Clone)]
pub(crate) struct NativeStereoCameraFrame {
    pub(crate) left: NativeCameraFrame,
    pub(crate) right: NativeCameraFrame,
    pub(crate) pair_delta_ns: u64,
}

#[derive(Debug)]
pub(crate) struct AndroidHardwareBufferHandle {
    ptr: *mut ndk_sys::AHardwareBuffer,
}

unsafe impl Send for AndroidHardwareBufferHandle {}
unsafe impl Sync for AndroidHardwareBufferHandle {}

impl AndroidHardwareBufferHandle {
    unsafe fn acquire(ptr: *mut ndk_sys::AHardwareBuffer) -> Result<Self, String> {
        if ptr.is_null() {
            return Err("AHardwareBuffer pointer is null".to_string());
        }
        ndk_sys::AHardwareBuffer_acquire(ptr);
        Ok(Self { ptr })
    }

    pub(crate) fn as_ptr(&self) -> *mut ndk_sys::AHardwareBuffer {
        self.ptr
    }
}

impl Clone for AndroidHardwareBufferHandle {
    fn clone(&self) -> Self {
        unsafe {
            ndk_sys::AHardwareBuffer_acquire(self.ptr);
        }
        Self { ptr: self.ptr }
    }
}

impl Drop for AndroidHardwareBufferHandle {
    fn drop(&mut self) {
        unsafe {
            ndk_sys::AHardwareBuffer_release(self.ptr);
        }
    }
}

struct NativeCameraStream {
    camera_id: String,
    side: CameraSide,
    capture_session: *mut ACameraCaptureSession,
    output_container: *mut ACaptureSessionOutputContainer,
    output: *mut ACaptureSessionOutput,
    camera_device: *mut ACameraDevice,
    target: *mut ACameraOutputTarget,
    window: *mut ACameraWindowType,
    reader: *mut AImageReader,
    capture_request: *mut ACaptureRequest,
    context: *mut NativeReaderContext,
}

impl NativeCameraStream {
    unsafe fn start(
        manager: *mut ACameraManager,
        side: CameraSide,
        camera_id: &str,
        counters: Arc<NativeCameraCounters>,
        alive: Arc<AtomicBool>,
    ) -> Result<Self, String> {
        let camera_id_c =
            CString::new(camera_id).map_err(|_| format!("camera id contains NUL: {camera_id}"))?;
        let context = Box::into_raw(Box::new(NativeReaderContext {
            side,
            camera_id: camera_id.to_string(),
            counters,
            alive,
        }));
        match Self::prepare(manager, side, &camera_id_c, context) {
            Ok(stream) => Ok(stream),
            Err(error) => {
                drop(Box::from_raw(context));
                Err(error)
            }
        }
    }

    unsafe fn prepare(
        manager: *mut ACameraManager,
        side: CameraSide,
        camera_id: &CString,
        context: *mut NativeReaderContext,
    ) -> Result<Self, String> {
        let mut device_callbacks = ACameraDevice_StateCallbacks {
            context: ptr::null_mut(),
            onDisconnected: Some(device_on_disconnected),
            onError: Some(device_on_error),
        };
        let mut camera_device = ptr::null_mut();
        if ACameraManager_openCamera(
            manager,
            camera_id.as_ptr(),
            &mut device_callbacks,
            &mut camera_device,
        ) != 0
            || camera_device.is_null()
        {
            return Err(format!(
                "ACameraManager_openCamera failed cameraId={}",
                camera_id.to_string_lossy()
            ));
        }

        let mut capture_request = ptr::null_mut();
        if ACameraDevice_createCaptureRequest(camera_device, TEMPLATE_PREVIEW, &mut capture_request)
            != 0
            || capture_request.is_null()
        {
            ACameraDevice_close(camera_device);
            return Err(format!(
                "ACameraDevice_createCaptureRequest failed cameraId={}",
                camera_id.to_string_lossy()
            ));
        }

        let mut reader = ptr::null_mut();
        let reader_result = AImageReader_newWithUsage(
            READER_WIDTH,
            READER_HEIGHT,
            AIMAGE_FORMAT_PRIVATE,
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
            READER_MAX_IMAGES,
            &mut reader,
        );
        if reader_result != 0 || reader.is_null() {
            ACaptureRequest_free(capture_request);
            ACameraDevice_close(camera_device);
            return Err(format!(
                "AImageReader_newWithUsage failed cameraId={} result={} size={}x{}",
                camera_id.to_string_lossy(),
                reader_result,
                READER_WIDTH,
                READER_HEIGHT
            ));
        }

        let mut listener = AImageReader_ImageListener {
            context: context.cast(),
            onImageAvailable: Some(image_on_image_available),
        };
        let _ = AImageReader_setImageListener(reader, &mut listener);

        let mut window = ptr::null_mut();
        if AImageReader_getWindow(reader, &mut window) != 0 || window.is_null() {
            AImageReader_delete(reader);
            ACaptureRequest_free(capture_request);
            ACameraDevice_close(camera_device);
            return Err(format!(
                "AImageReader_getWindow failed cameraId={}",
                camera_id.to_string_lossy()
            ));
        }
        ANativeWindow_acquire(window);

        let mut target = ptr::null_mut();
        if ACameraOutputTarget_create(window, &mut target) != 0 || target.is_null() {
            ANativeWindow_release(window);
            AImageReader_delete(reader);
            ACaptureRequest_free(capture_request);
            ACameraDevice_close(camera_device);
            return Err(format!(
                "ACameraOutputTarget_create failed cameraId={}",
                camera_id.to_string_lossy()
            ));
        }
        let _ = ACaptureRequest_addTarget(capture_request, target);

        let mut output = ptr::null_mut();
        if ACaptureSessionOutput_create(window, &mut output) != 0 || output.is_null() {
            ACaptureRequest_removeTarget(capture_request, target);
            ACameraOutputTarget_free(target);
            ANativeWindow_release(window);
            AImageReader_delete(reader);
            ACaptureRequest_free(capture_request);
            ACameraDevice_close(camera_device);
            return Err(format!(
                "ACaptureSessionOutput_create failed cameraId={}",
                camera_id.to_string_lossy()
            ));
        }

        let mut output_container = ptr::null_mut();
        ACaptureSessionOutputContainer_create(&mut output_container);
        if output_container.is_null() {
            ACaptureSessionOutput_free(output);
            ACaptureRequest_removeTarget(capture_request, target);
            ACameraOutputTarget_free(target);
            ANativeWindow_release(window);
            AImageReader_delete(reader);
            ACaptureRequest_free(capture_request);
            ACameraDevice_close(camera_device);
            return Err(format!(
                "ACaptureSessionOutputContainer_create failed cameraId={}",
                camera_id.to_string_lossy()
            ));
        }
        let _ = ACaptureSessionOutputContainer_add(output_container, output);

        let session_callbacks = ACameraCaptureSession_stateCallbacks {
            context: ptr::null_mut(),
            onClosed: Some(session_on_closed),
            onReady: Some(session_on_ready),
            onActive: Some(session_on_active),
        };
        let mut capture_session = ptr::null_mut();
        if ACameraDevice_createCaptureSession(
            camera_device,
            output_container,
            &session_callbacks,
            &mut capture_session,
        ) != 0
            || capture_session.is_null()
        {
            ACaptureSessionOutputContainer_free(output_container);
            ACaptureSessionOutput_free(output);
            ACaptureRequest_removeTarget(capture_request, target);
            ACameraOutputTarget_free(target);
            ANativeWindow_release(window);
            AImageReader_delete(reader);
            ACaptureRequest_free(capture_request);
            ACameraDevice_close(camera_device);
            return Err(format!(
                "ACameraDevice_createCaptureSession failed cameraId={}",
                camera_id.to_string_lossy()
            ));
        }

        let mut capture_request_for_repeating = capture_request;
        if ACameraCaptureSession_setRepeatingRequest(
            capture_session,
            ptr::null_mut(),
            1,
            &mut capture_request_for_repeating,
            ptr::null_mut(),
        ) != 0
        {
            ACameraCaptureSession_close(capture_session);
            ACaptureSessionOutputContainer_free(output_container);
            ACaptureSessionOutput_free(output);
            ACaptureRequest_removeTarget(capture_request, target);
            ACameraOutputTarget_free(target);
            ANativeWindow_release(window);
            AImageReader_delete(reader);
            ACaptureRequest_free(capture_request);
            ACameraDevice_close(camera_device);
            return Err(format!(
                "ACameraCaptureSession_setRepeatingRequest failed cameraId={}",
                camera_id.to_string_lossy()
            ));
        }

        marker(
            "camera-start",
            format!(
                "status=ok side={} cameraId={} width={} height={} format=PRIVATE usage=GPU_SAMPLED_IMAGE readerMaxImages={}",
                side.stable_id(),
                camera_id.to_string_lossy(),
                READER_WIDTH,
                READER_HEIGHT,
                READER_MAX_IMAGES
            ),
        );

        Ok(Self {
            camera_id: camera_id.to_string_lossy().into_owned(),
            side,
            capture_session,
            output_container,
            output,
            camera_device,
            target,
            window,
            reader,
            capture_request,
            context,
        })
    }
}

impl Drop for NativeCameraStream {
    fn drop(&mut self) {
        unsafe {
            if !self.context.is_null() {
                (*self.context).alive.store(false, Ordering::Release);
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
                ANativeWindow_release(self.window);
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
            if !self.context.is_null() {
                drop(Box::from_raw(self.context));
                self.context = ptr::null_mut();
            }
        }
        marker(
            "camera-stop",
            format!(
                "status=ok side={} cameraId={}",
                self.side.stable_id(),
                self.camera_id
            ),
        );
    }
}

#[derive(Clone, Copy)]
enum CameraSide {
    Left,
    Right,
}

impl CameraSide {
    const fn stable_id(self) -> &'static str {
        match self {
            Self::Left => "left",
            Self::Right => "right",
        }
    }
}

struct NativeReaderContext {
    side: CameraSide,
    camera_id: String,
    counters: Arc<NativeCameraCounters>,
    alive: Arc<AtomicBool>,
}

#[derive(Default)]
struct NativeCameraCounters {
    camera_frames_acquired: AtomicU64,
    hardware_buffer_imports: AtomicU64,
    hardware_buffer_cache_hits: AtomicU64,
    hardware_buffer_cache_misses: AtomicU64,
    guide_graph_renders: AtomicU64,
    guide_graph_cache_hits: AtomicU64,
    sdf_field_updates: AtomicU64,
    private_layer_invocations: AtomicU64,
    xr_frames_submitted: AtomicU64,
    stale_frames: AtomicU64,
    release_retire_count: AtomicU64,
    camera_acquire_errors: AtomicU64,
    last_left_timestamp_ns: AtomicI64,
    last_right_timestamp_ns: AtomicI64,
    latest_left_frame: Mutex<Option<NativeCameraFrame>>,
    latest_right_frame: Mutex<Option<NativeCameraFrame>>,
}

impl NativeCameraCounters {
    fn snapshot(&self) -> NativeCounterSnapshot {
        NativeCounterSnapshot {
            camera_frames_acquired: self.camera_frames_acquired.load(Ordering::Relaxed),
            hardware_buffer_imports: self.hardware_buffer_imports.load(Ordering::Relaxed),
            hardware_buffer_cache_hits: self.hardware_buffer_cache_hits.load(Ordering::Relaxed),
            hardware_buffer_cache_misses: self.hardware_buffer_cache_misses.load(Ordering::Relaxed),
            guide_graph_renders: self.guide_graph_renders.load(Ordering::Relaxed),
            guide_graph_cache_hits: self.guide_graph_cache_hits.load(Ordering::Relaxed),
            sdf_field_updates: self.sdf_field_updates.load(Ordering::Relaxed),
            private_layer_invocations: self.private_layer_invocations.load(Ordering::Relaxed),
            xr_frames_submitted: self.xr_frames_submitted.load(Ordering::Relaxed),
            stale_frames: self.stale_frames.load(Ordering::Relaxed),
            release_retire_count: self.release_retire_count.load(Ordering::Relaxed),
        }
    }

    fn latest_stereo_frame(&self) -> Option<NativeStereoCameraFrame> {
        let left = self.latest_left_frame.lock().ok()?.clone()?;
        let right = self.latest_right_frame.lock().ok()?.clone()?;
        let pair_delta_ns = left.timestamp_ns.abs_diff(right.timestamp_ns);
        Some(NativeStereoCameraFrame {
            left,
            right,
            pair_delta_ns,
        })
    }
}

pub(crate) struct NativeCounterSnapshot {
    pub(crate) camera_frames_acquired: u64,
    pub(crate) hardware_buffer_imports: u64,
    pub(crate) hardware_buffer_cache_hits: u64,
    pub(crate) hardware_buffer_cache_misses: u64,
    pub(crate) guide_graph_renders: u64,
    pub(crate) guide_graph_cache_hits: u64,
    pub(crate) sdf_field_updates: u64,
    pub(crate) private_layer_invocations: u64,
    pub(crate) xr_frames_submitted: u64,
    pub(crate) stale_frames: u64,
    pub(crate) release_retire_count: u64,
}

unsafe extern "C" fn image_on_image_available(context: *mut c_void, reader: *mut AImageReader) {
    if context.is_null() {
        return;
    }
    let reader_context = &*(context as *mut NativeReaderContext);
    if !reader_context.alive.load(Ordering::Acquire) {
        return;
    }

    let mut image: *mut AImage = ptr::null_mut();
    if AImageReader_acquireLatestImage(reader, &mut image) != 0 || image.is_null() {
        reader_context
            .counters
            .camera_acquire_errors
            .fetch_add(1, Ordering::Relaxed);
        return;
    }

    let mut timestamp_ns = 0_i64;
    let _ = AImage_getTimestamp(image, &mut timestamp_ns);
    let mut hardware_buffer_ptr = ptr::null_mut();
    if AImage_getHardwareBuffer(image, &mut hardware_buffer_ptr) == 0
        && !hardware_buffer_ptr.is_null()
    {
        let hardware_buffer = match AndroidHardwareBufferHandle::acquire(hardware_buffer_ptr) {
            Ok(handle) => handle,
            Err(error) => {
                reader_context
                    .counters
                    .camera_acquire_errors
                    .fetch_add(1, Ordering::Relaxed);
                android_log_error(format!(
                    "ACamera AHardwareBuffer acquire failed cameraId={} side={} error={}",
                    reader_context.camera_id,
                    reader_context.side.stable_id(),
                    error
                ));
                AImage_delete(image);
                return;
            }
        };
        let frame_sequence = reader_context
            .counters
            .camera_frames_acquired
            .fetch_add(1, Ordering::Relaxed)
            + 1;
        let import_sequence = reader_context
            .counters
            .hardware_buffer_imports
            .fetch_add(1, Ordering::Relaxed)
            + 1;
        let release_retire_count = reader_context
            .counters
            .release_retire_count
            .fetch_add(1, Ordering::Relaxed)
            + 1;
        match reader_context.side {
            CameraSide::Left => reader_context
                .counters
                .last_left_timestamp_ns
                .store(timestamp_ns, Ordering::Relaxed),
            CameraSide::Right => reader_context
                .counters
                .last_right_timestamp_ns
                .store(timestamp_ns, Ordering::Relaxed),
        }

        let mut desc = std::mem::MaybeUninit::<ndk_sys::AHardwareBuffer_Desc>::zeroed();
        ndk_sys::AHardwareBuffer_describe(hardware_buffer.as_ptr(), desc.as_mut_ptr());
        let desc = desc.assume_init();
        let mut hardware_buffer_id = 0_u64;
        let id_status =
            ndk_sys::AHardwareBuffer_getId(hardware_buffer.as_ptr(), &mut hardware_buffer_id);
        if id_status != 0 {
            hardware_buffer_id = 0;
        }
        let frame = NativeCameraFrame {
            side: reader_context.side.stable_id(),
            camera_id: reader_context.camera_id.clone(),
            width: desc.width,
            height: desc.height,
            timestamp_ns,
            source_frame: frame_sequence,
            import_sequence,
            hardware_buffer_id,
            native_format: desc.format,
            usage: desc.usage,
            layers: desc.layers,
            stride: desc.stride,
            hardware_buffer,
        };
        let latest_slot = match reader_context.side {
            CameraSide::Left => &reader_context.counters.latest_left_frame,
            CameraSide::Right => &reader_context.counters.latest_right_frame,
        };
        if let Ok(mut latest_frame) = latest_slot.lock() {
            *latest_frame = Some(frame);
        }
        marker(
            "hwb-frame-acquired",
            format!(
                "sourceFrame={} cameraId={} side={} hardwareBufferId={} importSequence={} timestampNs={} descriptorShape=combined-immutable-sampler-ycbcr-conversion descriptorWidth={} descriptorHeight={} descriptorLayers={} descriptorFormat={} descriptorUsage={} descriptorStride={} textureUpdateCadence=on-camera-frame releaseRetireCount={} hwbNativeImportReady=true gpuImportWorked=false vulkanExternalImportReady=false visualAcceptance=false",
                frame_sequence,
                reader_context.camera_id,
                reader_context.side.stable_id(),
                hardware_buffer_id,
                import_sequence,
                timestamp_ns,
                desc.width,
                desc.height,
                desc.layers,
                desc.format,
                desc.usage,
                desc.stride,
                release_retire_count
            ),
        );
    } else {
        reader_context
            .counters
            .camera_acquire_errors
            .fetch_add(1, Ordering::Relaxed);
        android_log_error(format!(
            "ACamera image missing AHardwareBuffer cameraId={} side={}",
            reader_context.camera_id,
            reader_context.side.stable_id()
        ));
    }
    AImage_delete(image);
}

unsafe extern "C" fn device_on_disconnected(_context: *mut c_void, _device: *mut ACameraDevice) {
    marker("camera-device", "event=disconnected");
}

unsafe extern "C" fn device_on_error(
    _context: *mut c_void,
    _device: *mut ACameraDevice,
    error: i32,
) {
    marker("camera-device", format!("event=error code={error}"));
}

unsafe extern "C" fn session_on_closed(
    _context: *mut c_void,
    _session: *mut ACameraCaptureSession,
) {
    marker("camera-session", "event=closed");
}

unsafe extern "C" fn session_on_ready(_context: *mut c_void, _session: *mut ACameraCaptureSession) {
    marker("camera-session", "event=ready");
}

unsafe extern "C" fn session_on_active(
    _context: *mut c_void,
    _session: *mut ACameraCaptureSession,
) {
    marker("camera-session", "event=active");
}

unsafe fn enumerate_camera_ids(manager: *mut ACameraManager) -> Result<Vec<String>, String> {
    let mut camera_ids_ptr = ptr::null_mut();
    if ACameraManager_getCameraIdList(manager, &mut camera_ids_ptr) != 0 || camera_ids_ptr.is_null()
    {
        return Err("ACameraManager_getCameraIdList failed".to_string());
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
