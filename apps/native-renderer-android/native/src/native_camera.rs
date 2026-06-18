//! Native Android Camera2/HWB runtime for the public blur renderer scaffold.

use std::{
    ffi::{CStr, CString},
    os::raw::{c_char, c_void},
    ptr,
    sync::{
        atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering},
        Arc, Mutex,
    },
};

use rusty_quest_native_renderer_contracts::NativeRendererPlan;

use crate::native_camera_metadata::{
    NativeCameraCaptureResultCorrelation, NativeCameraCaptureResultRing,
    NativeCameraCaptureResultSnapshot,
};
use crate::{
    acamera_sys::{
        ACameraCaptureFailure, ACameraCaptureSession, ACameraCaptureSession_captureCallbacks,
        ACameraCaptureSession_close, ACameraCaptureSession_setRepeatingRequest,
        ACameraCaptureSession_stateCallbacks, ACameraCaptureSession_stopRepeating, ACameraDevice,
        ACameraDevice_StateCallbacks, ACameraDevice_close, ACameraDevice_createCaptureRequest,
        ACameraDevice_createCaptureSession, ACameraManager, ACameraManager_create,
        ACameraManager_delete, ACameraManager_deleteCameraIdList,
        ACameraManager_getCameraCharacteristics, ACameraManager_getCameraIdList,
        ACameraManager_openCamera, ACameraMetadata, ACameraMetadataConstEntry,
        ACameraMetadata_free, ACameraMetadata_getConstEntry, ACameraOutputTarget,
        ACameraOutputTarget_create, ACameraOutputTarget_free, ACameraWindowType, ACaptureRequest,
        ACaptureRequest_addTarget, ACaptureRequest_free, ACaptureRequest_removeTarget,
        ACaptureRequest_setEntry_i32, ACaptureRequest_setEntry_u8, ACaptureSessionOutput,
        ACaptureSessionOutputContainer, ACaptureSessionOutputContainer_add,
        ACaptureSessionOutputContainer_create, ACaptureSessionOutputContainer_free,
        ACaptureSessionOutput_create, ACaptureSessionOutput_free, AImage, AImageReader,
        AImageReader_BufferRemovedListener, AImageReader_ImageListener,
        AImageReader_acquireLatestImage, AImageReader_delete, AImageReader_getWindow,
        AImageReader_newWithUsage, AImageReader_setBufferRemovedListener,
        AImageReader_setImageListener, AImage_delete, AImage_getHardwareBuffer,
        AImage_getTimestamp, ANativeWindow_acquire, ANativeWindow_release,
        ACAMERA_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, ACAMERA_CONTROL_AE_TARGET_FPS_RANGE,
        ACAMERA_EDGE_AVAILABLE_EDGE_MODES, ACAMERA_EDGE_MODE, ACAMERA_EDGE_MODE_FAST,
        ACAMERA_EDGE_MODE_HIGH_QUALITY, ACAMERA_EDGE_MODE_OFF,
        ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL,
        ACAMERA_NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, ACAMERA_NOISE_REDUCTION_MODE,
        ACAMERA_NOISE_REDUCTION_MODE_FAST, ACAMERA_NOISE_REDUCTION_MODE_HIGH_QUALITY,
        ACAMERA_NOISE_REDUCTION_MODE_OFF, ACAMERA_REQUEST_AVAILABLE_CAPABILITIES,
        ACAMERA_REQUEST_AVAILABLE_REQUEST_KEYS, ACAMERA_REQUEST_AVAILABLE_RESULT_KEYS,
        ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS,
        ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
        AIMAGE_FORMAT_PRIVATE,
    },
    android_log_error, marker,
    native_camera_profiles::CameraRequestTemplate,
    native_camera_reader_selection::{
        select_reader_size, CameraCapabilities, PrivateOutputMinFrameDuration,
    },
    native_renderer_options::{
        NativeCameraQualityProfile, NativeCameraResolutionProfile, NativeCameraSyncMode,
    },
};

const DEFAULT_READER_WIDTH: i32 = 1280;
const DEFAULT_READER_HEIGHT: i32 = 1280;
const DEFAULT_READER_MAX_IMAGES: u32 = 4;

pub(crate) struct NativeCameraRuntime {
    manager: *mut ACameraManager,
    counters: Arc<NativeCameraCounters>,
    left: Option<NativeCameraStream>,
    right: Option<NativeCameraStream>,
}

impl NativeCameraRuntime {
    pub(crate) fn start_from_plan(
        plan: &NativeRendererPlan,
        camera_resolution_profile: NativeCameraResolutionProfile,
        camera_reader_max_images: u32,
        camera_quality_profile: NativeCameraQualityProfile,
        camera_sync_mode: NativeCameraSyncMode,
    ) -> Result<Self, String> {
        let camera_reader_max_images = if camera_reader_max_images == 0 {
            DEFAULT_READER_MAX_IMAGES
        } else {
            camera_reader_max_images.clamp(3, 12)
        };
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
                camera_resolution_profile,
                camera_reader_max_images,
                camera_quality_profile,
                camera_sync_mode,
                Arc::clone(&counters),
                Arc::clone(&alive),
            )?
        });
        runtime.right = Some(unsafe {
            NativeCameraStream::start(
                manager,
                CameraSide::Right,
                &plan.camera_source.camera_ids.right,
                camera_resolution_profile,
                camera_reader_max_images,
                camera_quality_profile,
                camera_sync_mode,
                Arc::clone(&counters),
                alive,
            )?
        });

        marker(
            "camera-runtime",
            format!(
                "status=started acquisition=ACameraManager imageFormat=PRIVATE usage=GPU_SAMPLED_IMAGE textureUpdateCadence=on-camera-frame sourceFrame=ndk-callback cameraIds=50,51 cameraResolutionProfile={} readerMaxImages={} cameraQualityProfile={} cameraSyncRequested={} cameraSyncActive={} cameraSyncImplementation={}",
                camera_resolution_profile.marker_value(),
                camera_reader_max_images,
                camera_quality_profile.marker_value(),
                camera_sync_mode.marker_value(),
                camera_sync_mode.active_marker_value(),
                camera_sync_mode.implementation_status()
            ),
        );
        Ok(runtime)
    }

    pub(crate) fn counter_snapshot(&self) -> NativeCounterSnapshot {
        self.counters.snapshot()
    }

    pub(crate) fn latest_stereo_frame(&self) -> Option<NativeStereoCameraFrame> {
        self.counters.latest_stereo_frame()
    }

    pub(crate) fn take_removed_hardware_buffer_ids(&self) -> Vec<u64> {
        self.counters.take_removed_hardware_buffer_ids()
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
    pub(crate) image_lease: Option<Arc<NativeCameraImageLease>>,
    pub(crate) capture_result: NativeCameraCaptureResultCorrelation,
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

pub(crate) struct NativeCameraImageLease {
    image: *mut AImage,
    side: &'static str,
    camera_id: String,
    source_frame: u64,
    hardware_buffer_id: u64,
}

unsafe impl Send for NativeCameraImageLease {}
unsafe impl Sync for NativeCameraImageLease {}

impl NativeCameraImageLease {
    unsafe fn new(
        image: *mut AImage,
        side: CameraSide,
        camera_id: &str,
        source_frame: u64,
        hardware_buffer_id: u64,
    ) -> Arc<Self> {
        Arc::new(Self {
            image,
            side: side.stable_id(),
            camera_id: camera_id.to_string(),
            source_frame,
            hardware_buffer_id,
        })
    }
}

impl Drop for NativeCameraImageLease {
    fn drop(&mut self) {
        unsafe {
            if !self.image.is_null() {
                AImage_delete(self.image);
                self.image = ptr::null_mut();
            }
        }
        marker(
            "camera-sync",
            format!(
                "status=image-lease-retired side={} cameraId={} sourceFrame={} hardwareBufferId={} imageReleaseApi=AImage_delete releaseFenceFd=-1 cameraSyncActive=hold-image-until-gpu-fence producerConsumerSync=image-slot-held-until-vulkan-frame-fence",
                self.side, self.camera_id, self.source_frame, self.hardware_buffer_id
            ),
        );
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
    _capture_callbacks: Box<ACameraCaptureSession_captureCallbacks>,
    context: *mut NativeReaderContext,
}

impl NativeCameraStream {
    unsafe fn start(
        manager: *mut ACameraManager,
        side: CameraSide,
        camera_id: &str,
        camera_resolution_profile: NativeCameraResolutionProfile,
        camera_reader_max_images: u32,
        camera_quality_profile: NativeCameraQualityProfile,
        camera_sync_mode: NativeCameraSyncMode,
        counters: Arc<NativeCameraCounters>,
        alive: Arc<AtomicBool>,
    ) -> Result<Self, String> {
        let camera_id_c =
            CString::new(camera_id).map_err(|_| format!("camera id contains NUL: {camera_id}"))?;
        let context = Box::into_raw(Box::new(NativeReaderContext {
            side,
            camera_id: camera_id.to_string(),
            camera_resolution_profile,
            camera_reader_max_images,
            camera_quality_profile,
            camera_sync_mode,
            buffer_removed_listener_registered: false,
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
        let camera_resolution_profile = (*context).camera_resolution_profile;
        let reader_max_images = (*context).camera_reader_max_images as i32;
        let requested_reader_size = camera_resolution_profile
            .requested_size()
            .unwrap_or([DEFAULT_READER_WIDTH, DEFAULT_READER_HEIGHT]);
        let capabilities = match load_camera_capabilities(manager, camera_id.as_ptr()) {
            Ok(capabilities) => {
                marker(
                    "camera-capabilities",
                    format!(
                        "status=ok side={} cameraId={} cameraResolutionProfile={} readerRequested={}x{} hardwareLevel={} requestKeyCount={} resultKeyCount={} capabilities={} availableAeFpsRanges={} availableNoiseReductionModes={} availableEdgeModes={} privateOutputSizes={} privateOutputMinFrameDurations={}",
                        side.stable_id(),
                        camera_id.to_string_lossy(),
                        camera_resolution_profile.marker_value(),
                        requested_reader_size[0],
                        requested_reader_size[1],
                        optional_u8_marker(capabilities.hardware_level),
                        capabilities.request_keys.len(),
                        capabilities.result_keys.len(),
                        u8_list_marker(&capabilities.capabilities),
                        ae_fps_ranges_marker(&capabilities.ae_fps_ranges),
                        u8_modes_marker(&capabilities.noise_reduction_modes, noise_reduction_mode_label),
                        u8_modes_marker(&capabilities.edge_modes, edge_mode_label),
                        stream_sizes_marker(&capabilities.private_output_sizes),
                        stream_min_frame_durations_marker(
                            &capabilities.private_output_min_frame_durations
                        )
                    ),
                );
                capabilities
            }
            Err(error) => {
                marker(
                    "camera-capabilities",
                    format!(
                        "status=error side={} cameraId={} cameraResolutionProfile={} readerRequested={}x{} reason={}",
                        side.stable_id(),
                        camera_id.to_string_lossy(),
                        camera_resolution_profile.marker_value(),
                        requested_reader_size[0],
                        requested_reader_size[1],
                        crate::sanitize(&error)
                    ),
                );
                CameraCapabilities::default()
            }
        };
        let camera_quality_profile = (*context).camera_quality_profile;
        let reader_size = select_reader_size(
            camera_resolution_profile,
            camera_quality_profile,
            &capabilities,
            [DEFAULT_READER_WIDTH, DEFAULT_READER_HEIGHT],
        );

        let mut capture_request = ptr::null_mut();
        let request_template = camera_quality_profile.request_template();
        if ACameraDevice_createCaptureRequest(
            camera_device,
            request_template.ndk_value(),
            &mut capture_request,
        ) != 0
            || capture_request.is_null()
        {
            ACameraDevice_close(camera_device);
            return Err(format!(
                "ACameraDevice_createCaptureRequest failed cameraId={}",
                camera_id.to_string_lossy()
            ));
        }
        let camera_sync_mode = (*context).camera_sync_mode;
        apply_camera_quality_profile(
            capture_request,
            side,
            camera_id,
            camera_quality_profile,
            request_template,
            &capabilities,
        );

        let mut reader = ptr::null_mut();
        let reader_result = AImageReader_newWithUsage(
            reader_size.width,
            reader_size.height,
            AIMAGE_FORMAT_PRIVATE,
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
            reader_max_images,
            &mut reader,
        );
        if reader_result != 0 || reader.is_null() {
            ACaptureRequest_free(capture_request);
            ACameraDevice_close(camera_device);
            return Err(format!(
                "AImageReader_newWithUsage failed cameraId={} result={} size={}x{} readerMaxImages={}",
                camera_id.to_string_lossy(),
                reader_result,
                reader_size.width,
                reader_size.height,
                reader_max_images
            ));
        }

        let mut listener = AImageReader_ImageListener {
            context: context.cast(),
            onImageAvailable: Some(image_on_image_available),
        };
        let _ = AImageReader_setImageListener(reader, &mut listener);
        let mut buffer_removed_listener = AImageReader_BufferRemovedListener {
            context: context.cast(),
            onBufferRemoved: Some(image_on_buffer_removed),
        };
        let buffer_listener_status =
            AImageReader_setBufferRemovedListener(reader, &mut buffer_removed_listener);
        (*context).buffer_removed_listener_registered = buffer_listener_status == 0;
        marker(
            "camera-buffer-removed-listener",
            format!(
                "status={} side={} cameraId={} cacheEvictionSignal=true cacheEvictionQueued=true",
                if buffer_listener_status == 0 {
                    "registered"
                } else {
                    "error"
                },
                side.stable_id(),
                camera_id.to_string_lossy()
            ),
        );

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

        let mut capture_callbacks = Box::new(ACameraCaptureSession_captureCallbacks {
            context: context.cast(),
            onCaptureStarted: None,
            onCaptureProgressed: None,
            onCaptureCompleted: Some(capture_on_completed),
            onCaptureFailed: Some(capture_on_failed),
            onCaptureSequenceCompleted: Some(capture_on_sequence_completed),
            onCaptureSequenceAborted: Some(capture_on_sequence_aborted),
            onCaptureBufferLost: Some(capture_on_buffer_lost),
        });
        let mut capture_request_for_repeating = capture_request;
        if ACameraCaptureSession_setRepeatingRequest(
            capture_session,
            capture_callbacks.as_mut(),
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
                "status=ok side={} cameraId={} cameraResolutionProfile={} readerRequested={}x{} readerSelected={}x{} readerSelectionStatus={} readerSelectionReason={} readerMinFrameDurationNs={} readerTargetFps={} readerTargetFpsFeasible={} width={} height={} format=PRIVATE usage=GPU_SAMPLED_IMAGE readerMaxImages={}",
                side.stable_id(),
                camera_id.to_string_lossy(),
                camera_resolution_profile.marker_value(),
                reader_size.requested_width,
                reader_size.requested_height,
                reader_size.width,
                reader_size.height,
                reader_size.status,
                reader_size.reason,
                optional_i64_marker(reader_size.min_frame_duration_ns),
                optional_i32_marker(reader_size.target_fps),
                reader_size.target_fps_feasible
                    .map(|feasible| feasible.to_string())
                    .unwrap_or_else(|| "unknown".to_string()),
                reader_size.width,
                reader_size.height,
                reader_max_images
            ),
        );
        marker(
            "camera-sync",
            format!(
                "status=config side={} cameraId={} cameraQualityProfile={} cameraSyncRequested={} cameraSyncActive={} cameraSyncImplementation={} imageAcquireApi=AImageReader_acquireLatestImage acquireFenceFd=-1 imageReleaseApi={} releaseFenceFd=-1 producerConsumerSync={} ahbHandleRetained=true",
                side.stable_id(),
                camera_id.to_string_lossy(),
                camera_quality_profile.marker_value(),
                camera_sync_mode.marker_value(),
                camera_sync_mode.active_marker_value(),
                camera_sync_mode.implementation_status(),
                if matches!(camera_sync_mode, NativeCameraSyncMode::HoldImageUntilGpuFence) {
                    "AImage_delete-on-vulkan-frame-fence"
                } else {
                    "AImage_delete"
                },
                if matches!(camera_sync_mode, NativeCameraSyncMode::HoldImageUntilGpuFence) {
                    "image-slot-held-until-vulkan-frame-fence"
                } else {
                    "not-fence-backed-yet"
                },
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
            _capture_callbacks: capture_callbacks,
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

#[derive(Clone, Debug, Default)]
unsafe fn load_camera_capabilities(
    manager: *mut ACameraManager,
    camera_id: *const c_char,
) -> Result<CameraCapabilities, String> {
    let mut metadata: *mut ACameraMetadata = ptr::null_mut();
    let status = ACameraManager_getCameraCharacteristics(manager, camera_id, &mut metadata);
    if status != 0 || metadata.is_null() {
        return Err(format!(
            "ACameraManager_getCameraCharacteristics failed status={status}"
        ));
    }

    let mut capabilities = CameraCapabilities {
        hardware_level: read_u8_values(metadata, ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL)
            .first()
            .copied(),
        capabilities: read_u8_values(metadata, ACAMERA_REQUEST_AVAILABLE_CAPABILITIES),
        request_keys: read_i32_values(metadata, ACAMERA_REQUEST_AVAILABLE_REQUEST_KEYS),
        result_keys: read_i32_values(metadata, ACAMERA_REQUEST_AVAILABLE_RESULT_KEYS),
        ae_fps_ranges: read_i32_values(metadata, ACAMERA_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .chunks_exact(2)
            .map(|range| [range[0], range[1]])
            .collect(),
        noise_reduction_modes: read_u8_values(
            metadata,
            ACAMERA_NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
        ),
        edge_modes: read_u8_values(metadata, ACAMERA_EDGE_AVAILABLE_EDGE_MODES),
        private_output_sizes: Vec::new(),
        private_output_min_frame_durations: Vec::new(),
    };

    let stream_configs = read_i32_values(metadata, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS);
    for config in stream_configs.chunks_exact(4) {
        let format = config[0];
        let width = config[1];
        let height = config[2];
        let input = config[3];
        let size = [width, height];
        if format == AIMAGE_FORMAT_PRIVATE as i32
            && input == 0
            && !capabilities.private_output_sizes.contains(&size)
        {
            capabilities.private_output_sizes.push(size);
        }
    }
    let min_frame_durations =
        read_i64_values(metadata, ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS);
    for duration in min_frame_durations.chunks_exact(4) {
        let format = duration[0] as i32;
        let width = duration[1] as i32;
        let height = duration[2] as i32;
        let duration_ns = duration[3];
        if format == AIMAGE_FORMAT_PRIVATE as i32 && duration_ns > 0 {
            capabilities
                .private_output_min_frame_durations
                .push(PrivateOutputMinFrameDuration {
                    width,
                    height,
                    duration_ns,
                });
        }
    }

    ACameraMetadata_free(metadata);
    Ok(capabilities)
}

unsafe fn apply_camera_quality_profile(
    capture_request: *mut ACaptureRequest,
    side: CameraSide,
    camera_id: &CString,
    profile: NativeCameraQualityProfile,
    request_template: CameraRequestTemplate,
    capabilities: &CameraCapabilities,
) {
    let desired_ae_fps_range = profile.target_ae_fps_range();
    let desired_noise_modes = match profile {
        NativeCameraQualityProfile::DirectLowNoise30
        | NativeCameraQualityProfile::DirectLowNoiseRecord30
        | NativeCameraQualityProfile::DirectQualityProbe => vec![
            ACAMERA_NOISE_REDUCTION_MODE_HIGH_QUALITY,
            ACAMERA_NOISE_REDUCTION_MODE_FAST,
        ],
        NativeCameraQualityProfile::DirectLowLatency60 => {
            vec![ACAMERA_NOISE_REDUCTION_MODE_FAST]
        }
        NativeCameraQualityProfile::DirectBaseline => Vec::new(),
    };
    let desired_edge_modes = match profile {
        NativeCameraQualityProfile::DirectLowNoise30
        | NativeCameraQualityProfile::DirectLowNoiseRecord30
        | NativeCameraQualityProfile::DirectLowLatency60
        | NativeCameraQualityProfile::DirectQualityProbe => vec![ACAMERA_EDGE_MODE_OFF],
        NativeCameraQualityProfile::DirectBaseline => Vec::new(),
    };

    let (selected_ae_fps_range, applied_ae_fps_range, ae_fps_status) =
        apply_ae_fps_range(capture_request, capabilities, desired_ae_fps_range);
    let requested_noise_mode = desired_noise_modes.first().copied();
    let selected_noise_mode = desired_noise_modes
        .iter()
        .copied()
        .find(|mode| capabilities.supports_noise_reduction_mode(*mode));
    let (applied_noise_mode, noise_status) = apply_u8_request(
        capture_request,
        ACAMERA_NOISE_REDUCTION_MODE,
        selected_noise_mode,
        requested_noise_mode,
    );
    let requested_edge_mode = desired_edge_modes.first().copied();
    let selected_edge_mode = desired_edge_modes
        .iter()
        .copied()
        .find(|mode| capabilities.supports_edge_mode(*mode));
    let (applied_edge_mode, edge_status) = apply_u8_request(
        capture_request,
        ACAMERA_EDGE_MODE,
        selected_edge_mode,
        requested_edge_mode,
    );

    marker(
        "camera-request-profile",
        format!(
            "status=config side={} cameraId={} profile={} template={} requestedAeFpsRange={} selectedAeFpsRange={} appliedAeFpsRange={} aeFpsStatus={} requestedNoiseReductionMode={} appliedNoiseReductionMode={} noiseReductionStatus={} requestedEdgeMode={} appliedEdgeMode={} edgeStatus={} requestKeyCount={} resultKeyCount={} supportGated=true",
            side.stable_id(),
            camera_id.to_string_lossy(),
            profile.marker_value(),
            request_template.marker_value(),
            optional_range_marker(desired_ae_fps_range),
            optional_range_marker(selected_ae_fps_range),
            optional_range_marker(applied_ae_fps_range),
            ae_fps_status,
            optional_u8_mode_marker(requested_noise_mode, noise_reduction_mode_label),
            optional_u8_mode_marker(applied_noise_mode, noise_reduction_mode_label),
            noise_status,
            optional_u8_mode_marker(requested_edge_mode, edge_mode_label),
            optional_u8_mode_marker(applied_edge_mode, edge_mode_label),
            edge_status,
            capabilities.request_keys.len(),
            capabilities.result_keys.len(),
        ),
    );
}

unsafe fn apply_ae_fps_range(
    capture_request: *mut ACaptureRequest,
    capabilities: &CameraCapabilities,
    requested: Option<[i32; 2]>,
) -> (Option<[i32; 2]>, Option<[i32; 2]>, String) {
    let Some(range) = requested else {
        return (None, None, "not-requested".to_string());
    };
    if !capabilities.supports_request_key(ACAMERA_CONTROL_AE_TARGET_FPS_RANGE) {
        return (None, None, "request-key-unsupported".to_string());
    }
    let Some(selected) = select_ae_fps_range(range, &capabilities.ae_fps_ranges) else {
        return (None, None, "unsupported".to_string());
    };
    let status = ACaptureRequest_setEntry_i32(
        capture_request,
        ACAMERA_CONTROL_AE_TARGET_FPS_RANGE,
        2,
        selected.as_ptr(),
    );
    let selection_status = if selected == range {
        "exact-supported"
    } else {
        "nearest-supported"
    };
    if status == 0 {
        (
            Some(selected),
            Some(selected),
            format!("set-{selection_status}"),
        )
    } else {
        (
            Some(selected),
            None,
            format!("set-error-{status}-{selection_status}"),
        )
    }
}

fn select_ae_fps_range(requested: [i32; 2], supported: &[[i32; 2]]) -> Option<[i32; 2]> {
    supported
        .iter()
        .copied()
        .find(|range| *range == requested)
        .or_else(|| {
            supported.iter().copied().min_by_key(|range| {
                (
                    (range[0] - requested[0]).abs() + (range[1] - requested[1]).abs(),
                    (range[1] - range[0]).abs(),
                    (range[1] - requested[1]).abs(),
                )
            })
        })
}

unsafe fn apply_u8_request(
    capture_request: *mut ACaptureRequest,
    tag: u32,
    selected: Option<u8>,
    requested: Option<u8>,
) -> (Option<u8>, String) {
    let Some(value) = selected else {
        return (
            None,
            if requested.is_some() {
                "unsupported".to_string()
            } else {
                "not-requested".to_string()
            },
        );
    };
    let values = [value];
    let status = ACaptureRequest_setEntry_u8(capture_request, tag, 1, values.as_ptr());
    if status == 0 {
        (Some(value), "set".to_string())
    } else {
        (None, format!("set-error-{status}"))
    }
}

unsafe fn read_u8_values(metadata: *const ACameraMetadata, tag: u32) -> Vec<u8> {
    let Some(entry) = metadata_entry(metadata, tag) else {
        return Vec::new();
    };
    if entry.count == 0 || entry.data.u8_.is_null() {
        return Vec::new();
    }
    std::slice::from_raw_parts(entry.data.u8_, entry.count as usize).to_vec()
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

unsafe fn read_i64_values(metadata: *const ACameraMetadata, tag: u32) -> Vec<i64> {
    let Some(entry) = metadata_entry(metadata, tag) else {
        return Vec::new();
    };
    if entry.count == 0 || entry.data.i64_.is_null() {
        return Vec::new();
    }
    std::slice::from_raw_parts(entry.data.i64_, entry.count as usize).to_vec()
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

fn optional_u8_marker(value: Option<u8>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "unavailable".to_string())
}

fn u8_list_marker(values: &[u8]) -> String {
    if values.is_empty() {
        return "none".to_string();
    }
    values
        .iter()
        .map(|value| value.to_string())
        .collect::<Vec<_>>()
        .join(",")
}

fn u8_modes_marker(values: &[u8], label: fn(u8) -> &'static str) -> String {
    if values.is_empty() {
        return "none".to_string();
    }
    values
        .iter()
        .map(|value| label(*value))
        .collect::<Vec<_>>()
        .join(",")
}

fn ae_fps_ranges_marker(values: &[[i32; 2]]) -> String {
    if values.is_empty() {
        return "none".to_string();
    }
    values
        .iter()
        .map(|range| format!("{}-{}", range[0], range[1]))
        .collect::<Vec<_>>()
        .join(",")
}

fn stream_sizes_marker(values: &[[i32; 2]]) -> String {
    if values.is_empty() {
        return "none".to_string();
    }
    values
        .iter()
        .map(|size| format!("{}x{}", size[0], size[1]))
        .collect::<Vec<_>>()
        .join(",")
}

fn stream_min_frame_durations_marker(values: &[PrivateOutputMinFrameDuration]) -> String {
    if values.is_empty() {
        return "none".to_string();
    }
    values
        .iter()
        .map(|value| format!("{}x{}:{}", value.width, value.height, value.duration_ns))
        .collect::<Vec<_>>()
        .join(",")
}

fn optional_range_marker(value: Option<[i32; 2]>) -> String {
    value
        .map(|range| format!("{}-{}", range[0], range[1]))
        .unwrap_or_else(|| "none".to_string())
}

fn optional_i32_marker(value: Option<i32>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "none".to_string())
}

fn optional_i64_marker(value: Option<i64>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "none".to_string())
}

fn optional_u8_mode_marker(value: Option<u8>, label: fn(u8) -> &'static str) -> String {
    value
        .map(|mode| label(mode).to_string())
        .unwrap_or_else(|| "none".to_string())
}

fn noise_reduction_mode_label(value: u8) -> &'static str {
    match value {
        ACAMERA_NOISE_REDUCTION_MODE_OFF => "OFF",
        ACAMERA_NOISE_REDUCTION_MODE_FAST => "FAST",
        ACAMERA_NOISE_REDUCTION_MODE_HIGH_QUALITY => "HIGH_QUALITY",
        _ => "UNKNOWN",
    }
}

fn edge_mode_label(value: u8) -> &'static str {
    match value {
        ACAMERA_EDGE_MODE_OFF => "OFF",
        ACAMERA_EDGE_MODE_FAST => "FAST",
        ACAMERA_EDGE_MODE_HIGH_QUALITY => "HIGH_QUALITY",
        _ => "UNKNOWN",
    }
}

struct NativeReaderContext {
    side: CameraSide,
    camera_id: String,
    camera_resolution_profile: NativeCameraResolutionProfile,
    camera_reader_max_images: u32,
    camera_quality_profile: NativeCameraQualityProfile,
    camera_sync_mode: NativeCameraSyncMode,
    buffer_removed_listener_registered: bool,
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
    capture_results_seen: AtomicU64,
    last_left_timestamp_ns: AtomicI64,
    last_right_timestamp_ns: AtomicI64,
    removed_hardware_buffer_ids: Mutex<Vec<u64>>,
    latest_left_frame: Mutex<Option<NativeCameraFrame>>,
    latest_right_frame: Mutex<Option<NativeCameraFrame>>,
    recent_left_capture_results: Mutex<NativeCameraCaptureResultRing>,
    recent_right_capture_results: Mutex<NativeCameraCaptureResultRing>,
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

    fn push_removed_hardware_buffer_id(&self, hardware_buffer_id: u64) {
        if hardware_buffer_id == 0 {
            return;
        }
        if let Ok(mut removed) = self.removed_hardware_buffer_ids.lock() {
            if !removed.contains(&hardware_buffer_id) {
                removed.push(hardware_buffer_id);
            }
        }
    }

    fn take_removed_hardware_buffer_ids(&self) -> Vec<u64> {
        self.removed_hardware_buffer_ids
            .lock()
            .map(|mut removed| std::mem::take(&mut *removed))
            .unwrap_or_default()
    }

    fn push_capture_result(&self, side: CameraSide, snapshot: NativeCameraCaptureResultSnapshot) {
        let result_slot = match side {
            CameraSide::Left => &self.recent_left_capture_results,
            CameraSide::Right => &self.recent_right_capture_results,
        };
        if let Ok(mut results) = result_slot.lock() {
            results.push(snapshot);
        }
    }

    fn correlate_capture_result(
        &self,
        side: CameraSide,
        timestamp_ns: i64,
    ) -> NativeCameraCaptureResultCorrelation {
        let result_slot = match side {
            CameraSide::Left => &self.recent_left_capture_results,
            CameraSide::Right => &self.recent_right_capture_results,
        };
        result_slot
            .lock()
            .map(|results| results.correlate(timestamp_ns))
            .unwrap_or_default()
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
    let acquire_result = AImageReader_acquireLatestImage(reader, &mut image);
    if acquire_result != 0 || image.is_null() {
        let acquire_error_count = reader_context
            .counters
            .camera_acquire_errors
            .fetch_add(1, Ordering::Relaxed)
            + 1;
        marker(
            "camera-acquire",
            format!(
                "status=error side={} cameraId={} acquireResult={} imageNull={} readerMaxImages={} acquireErrorCount={} queueExhaustionPossible=true imageAcquireApi=AImageReader_acquireLatestImage",
                reader_context.side.stable_id(),
                reader_context.camera_id,
                acquire_result,
                image.is_null(),
                reader_context.camera_reader_max_images,
                acquire_error_count
            ),
        );
        return;
    }
    let hold_image_until_gpu_fence = matches!(
        reader_context.camera_sync_mode,
        NativeCameraSyncMode::HoldImageUntilGpuFence
    );
    let mut image_retained_by_frame = false;

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
        let image_lease = if hold_image_until_gpu_fence {
            image_retained_by_frame = true;
            Some(NativeCameraImageLease::new(
                image,
                reader_context.side,
                &reader_context.camera_id,
                frame_sequence,
                hardware_buffer_id,
            ))
        } else {
            None
        };
        let capture_result = reader_context
            .counters
            .correlate_capture_result(reader_context.side, timestamp_ns);
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
            image_lease,
            capture_result: capture_result.clone(),
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
                "sourceFrame={} cameraId={} side={} hardwareBufferId={} importSequence={} timestampNs={} descriptorShape=combined-immutable-sampler-ycbcr-conversion descriptorWidth={} descriptorHeight={} descriptorLayers={} descriptorFormat={} descriptorUsage={} descriptorStride={} textureUpdateCadence=on-camera-frame releaseRetireCount={} cameraQualityProfile={} cameraSyncRequested={} cameraSyncActive={} cameraSyncImplementation={} imageAcquireApi=AImageReader_acquireLatestImage acquireFenceFd=-1 imageReleaseApi={} releaseFenceFd=-1 producerConsumerSync={} ahbHandleRetained=true imageLeaseActive={} bufferRemovedListenerRegistered={} hwbNativeImportReady=true gpuImportWorked=false vulkanExternalImportReady=false visualAcceptance=false {}",
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
                release_retire_count,
                reader_context.camera_quality_profile.marker_value(),
                reader_context.camera_sync_mode.marker_value(),
                reader_context.camera_sync_mode.active_marker_value(),
                reader_context.camera_sync_mode.implementation_status(),
                if hold_image_until_gpu_fence {
                    "AImage_delete-on-vulkan-frame-fence"
                } else {
                    "AImage_delete"
                },
                if hold_image_until_gpu_fence {
                    "image-slot-held-until-vulkan-frame-fence"
                } else {
                    "not-fence-backed-yet"
                },
                hold_image_until_gpu_fence,
                reader_context.buffer_removed_listener_registered,
                capture_result.frame_marker_fields()
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
    if !image_retained_by_frame {
        AImage_delete(image);
    }
}

unsafe extern "C" fn image_on_buffer_removed(
    context: *mut c_void,
    _reader: *mut AImageReader,
    buffer: *mut ndk_sys::AHardwareBuffer,
) {
    if context.is_null() {
        return;
    }
    let reader_context = &*(context as *mut NativeReaderContext);
    let mut hardware_buffer_id = 0_u64;
    let mut desc_marker = "unavailable".to_string();
    if !buffer.is_null() {
        if ndk_sys::AHardwareBuffer_getId(buffer, &mut hardware_buffer_id) != 0 {
            hardware_buffer_id = 0;
        }
        let mut desc = std::mem::MaybeUninit::<ndk_sys::AHardwareBuffer_Desc>::zeroed();
        ndk_sys::AHardwareBuffer_describe(buffer, desc.as_mut_ptr());
        let desc = desc.assume_init();
        desc_marker = format!(
            "{}x{} format={} usage={} stride={} layers={}",
            desc.width, desc.height, desc.format, desc.usage, desc.stride, desc.layers
        );
    }
    reader_context
        .counters
        .push_removed_hardware_buffer_id(hardware_buffer_id);
    marker(
        "hwb-buffer-removed",
        format!(
            "side={} cameraId={} hardwareBufferId={} descriptor={} cacheEvictionSignal=true cacheEvictionQueued={}",
            reader_context.side.stable_id(),
            reader_context.camera_id,
            hardware_buffer_id,
            desc_marker,
            hardware_buffer_id != 0
        ),
    );
}

unsafe extern "C" fn capture_on_completed(
    context: *mut c_void,
    _session: *mut ACameraCaptureSession,
    _request: *mut ACaptureRequest,
    result: *const ACameraMetadata,
) {
    if context.is_null() || result.is_null() {
        return;
    }
    let reader_context = &*(context as *mut NativeReaderContext);
    let result_count = reader_context
        .counters
        .capture_results_seen
        .fetch_add(1, Ordering::Relaxed)
        + 1;
    let snapshot = NativeCameraCaptureResultSnapshot::from_metadata(result_count, result);
    reader_context
        .counters
        .push_capture_result(reader_context.side, snapshot.clone());
    if result_count > 3 && result_count % 120 != 0 {
        return;
    }
    marker(
        "camera-capture-result",
        format!(
            "status=ok side={} cameraId={} resultCount={} cameraQualityProfile={} cameraSyncRequested={} cameraSyncActive={} {} resultMetadataReady=true",
            reader_context.side.stable_id(),
            reader_context.camera_id,
            result_count,
            reader_context.camera_quality_profile.marker_value(),
            reader_context.camera_sync_mode.marker_value(),
            reader_context.camera_sync_mode.active_marker_value(),
            snapshot.capture_result_marker_fields(),
        ),
    );
}

unsafe extern "C" fn capture_on_failed(
    context: *mut c_void,
    _session: *mut ACameraCaptureSession,
    _request: *mut ACaptureRequest,
    failure: *mut ACameraCaptureFailure,
) {
    if context.is_null() {
        return;
    }
    let reader_context = &*(context as *mut NativeReaderContext);
    let (frame_number, reason, sequence_id, was_image_captured) = if failure.is_null() {
        (-1, -1, -1, false)
    } else {
        (
            (*failure).frameNumber,
            (*failure).reason,
            (*failure).sequenceId,
            (*failure).wasImageCaptured,
        )
    };
    marker(
        "camera-capture-result",
        format!(
            "status=failed side={} cameraId={} frameNumber={} reason={} sequenceId={} wasImageCaptured={}",
            reader_context.side.stable_id(),
            reader_context.camera_id,
            frame_number,
            reason,
            sequence_id,
            was_image_captured
        ),
    );
}

unsafe extern "C" fn capture_on_sequence_completed(
    context: *mut c_void,
    _session: *mut ACameraCaptureSession,
    sequence_id: i32,
    frame_number: i64,
) {
    if context.is_null() {
        return;
    }
    let reader_context = &*(context as *mut NativeReaderContext);
    marker(
        "camera-capture-sequence",
        format!(
            "status=completed side={} cameraId={} sequenceId={} frameNumber={}",
            reader_context.side.stable_id(),
            reader_context.camera_id,
            sequence_id,
            frame_number
        ),
    );
}

unsafe extern "C" fn capture_on_sequence_aborted(
    context: *mut c_void,
    _session: *mut ACameraCaptureSession,
    sequence_id: i32,
) {
    if context.is_null() {
        return;
    }
    let reader_context = &*(context as *mut NativeReaderContext);
    marker(
        "camera-capture-sequence",
        format!(
            "status=aborted side={} cameraId={} sequenceId={}",
            reader_context.side.stable_id(),
            reader_context.camera_id,
            sequence_id
        ),
    );
}

unsafe extern "C" fn capture_on_buffer_lost(
    context: *mut c_void,
    _session: *mut ACameraCaptureSession,
    _request: *mut ACaptureRequest,
    _window: *mut ACameraWindowType,
    frame_number: i64,
) {
    if context.is_null() {
        return;
    }
    let reader_context = &*(context as *mut NativeReaderContext);
    marker(
        "camera-capture-result",
        format!(
            "status=buffer-lost side={} cameraId={} frameNumber={}",
            reader_context.side.stable_id(),
            reader_context.camera_id,
            frame_number
        ),
    );
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
