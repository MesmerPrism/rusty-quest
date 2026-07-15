use std::ffi::{c_void, CStr, CString};
use std::os::raw::c_int;
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU32, AtomicU64, Ordering};
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
    ACaptureRequest_removeTarget, ACaptureRequest_setEntry_i32, ACaptureRequest_setEntry_u8,
    ACaptureSessionOutput, ACaptureSessionOutputContainer, ACaptureSessionOutputContainer_add,
    ACaptureSessionOutputContainer_create, ACaptureSessionOutputContainer_free,
    ACaptureSessionOutput_create, ACaptureSessionOutput_free, AImage, AImageReader,
    AImageReader_ImageListener, AImageReader_acquireLatestImage, AImageReader_delete,
    AImageReader_getWindow, AImageReader_newWithUsage, AImageReader_setImageListener,
    AImage_delete, AImage_getHardwareBuffer, AImage_getTimestamp, ANativeWindow,
    ANativeWindow_acquire as ACameraNativeWindow_acquire,
    ANativeWindow_release as ACameraNativeWindow_release,
    ACAMERA_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, ACAMERA_CONTROL_AE_TARGET_FPS_RANGE,
    ACAMERA_EDGE_AVAILABLE_EDGE_MODES, ACAMERA_EDGE_MODE, ACAMERA_EDGE_MODE_OFF,
    ACAMERA_LENS_INTRINSIC_CALIBRATION, ACAMERA_LENS_POSE_REFERENCE, ACAMERA_LENS_POSE_ROTATION,
    ACAMERA_LENS_POSE_TRANSLATION, ACAMERA_NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
    ACAMERA_NOISE_REDUCTION_MODE, ACAMERA_NOISE_REDUCTION_MODE_OFF,
    ACAMERA_REQUEST_AVAILABLE_REQUEST_KEYS, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS,
    ACAMERA_SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE, ACAMERA_SENSOR_INFO_TIMESTAMP_SOURCE,
    AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, AIMAGE_FORMAT_PRIVATE, TEMPLATE_PREVIEW,
};
use crate::android_hardware_buffer::{
    AndroidHardwareBufferDescriptor, AndroidHardwareBufferHandle,
};
use crate::camera_hwb_marker::log_camera_hwb_marker as log_marker;
use crate::camera_hwb_projection_target::{
    camera_hwb_projection_marker_fields, CAMERA_HWB_LEFT_CAMERA_ID, CAMERA_HWB_RIGHT_CAMERA_ID,
};
use crate::camera_latency_diagnostics::{
    boottime_now_ns, camera_latency_capture_viewer_basis, camera_latency_per_frame_log_enabled,
    current_camera_latency_settings, update_camera_latency_camera_calibration,
    CameraLatencyCameraSyncMode, CameraLatencyCaptureProcessing, CameraLatencyViewerBasis,
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
    pub(crate) timestamp_source: CameraTimestampSource,
    pub(crate) callback_age_ns: Option<u64>,
    pub(crate) callback_boottime_ns: i64,
    pub(crate) source_delta_ns: Option<u64>,
    pub(crate) callback_delta_ns: Option<u64>,
    pub(crate) capture_viewer_basis: Option<CameraLatencyViewerBasis>,
    image_lease: Option<Arc<CameraProbeImageLease>>,
}

impl CameraProbeFrame {
    pub(crate) fn sensor_age_at_boottime(&self, now_ns: i64) -> Option<u64> {
        if self.timestamp_source != CameraTimestampSource::Realtime
            || self.timestamp_ns <= 0
            || now_ns < self.timestamp_ns
        {
            return None;
        }
        Some((now_ns - self.timestamp_ns) as u64)
    }

    pub(crate) fn has_fence_held_image(&self) -> bool {
        self.image_lease.is_some()
    }
}

struct CameraProbeImageLease {
    image: *mut AImage,
    side_label: &'static str,
    camera_id: String,
    frame_index: u64,
    hardware_buffer_id: u64,
    log_retirement: bool,
}

unsafe impl Send for CameraProbeImageLease {}
unsafe impl Sync for CameraProbeImageLease {}

impl CameraProbeImageLease {
    unsafe fn new(
        image: *mut AImage,
        side_label: &'static str,
        camera_id: &str,
        frame_index: u64,
        hardware_buffer_id: u64,
        log_retirement: bool,
    ) -> Arc<Self> {
        Arc::new(Self {
            image,
            side_label,
            camera_id: camera_id.to_string(),
            frame_index,
            hardware_buffer_id,
            log_retirement,
        })
    }
}

impl Drop for CameraProbeImageLease {
    fn drop(&mut self) {
        unsafe {
            if !self.image.is_null() {
                AImage_delete(self.image);
                self.image = ptr::null_mut();
            }
        }
        if self.log_retirement {
            log_marker(format!(
                "status=image-lease-retired side={} cameraId={} frameIndex={} hardwareBufferId={} imageReleaseApi=AImage_delete releaseFenceFd=-1 cameraSyncActive=hold-image-until-gpu-fence producerConsumerSync=image-slot-held-until-final-frame-reference-dropped",
                self.side_label,
                marker_token(&self.camera_id),
                self.frame_index,
                self.hardware_buffer_id,
            ));
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum CameraTimestampSource {
    Unavailable,
    Unknown,
    Realtime,
}

impl CameraTimestampSource {
    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::Unavailable => "unavailable",
            Self::Unknown => "unknown-camera-only-timebase",
            Self::Realtime => "realtime-elapsedRealtimeNanos",
        }
    }
}

struct CameraProbeStaticMetadata {
    private_output_sizes: Vec<[i32; 2]>,
    timestamp_source: CameraTimestampSource,
    lens_pose_translation: Vec<f32>,
    lens_pose_rotation: Vec<f32>,
    lens_intrinsic_calibration: Vec<f32>,
    lens_pose_reference: Option<u8>,
    pre_correction_active_array: Vec<i32>,
    ae_fps_ranges: Vec<[i32; 2]>,
    noise_reduction_modes: Vec<u8>,
    edge_modes: Vec<u8>,
    request_keys: Vec<i32>,
}

impl CameraProbeStaticMetadata {
    fn marker_fields(&self) -> String {
        format!(
            "sensorTimestampSource={} lensPoseTranslation={} lensPoseRotation={} lensIntrinsicCalibration={} lensPoseReference={} preCorrectionActiveArray={} aeAvailableTargetFpsRanges={} aeTargetFpsRequestKeySupported={} noiseReductionAvailableModes={} noiseReductionRequestKeySupported={} edgeAvailableModes={} edgeRequestKeySupported={} lensPoseMetadataLifecycle=static-camera-characteristics dynamicCameraPoseMetadataUsed=false imageTimestampPoseAssociation=selected-by-camera-latency-reprojection-mode captureResultMetadataCallbacks=false",
            self.timestamp_source.marker_token(),
            float_values_marker(&self.lens_pose_translation, 3),
            float_values_marker(&self.lens_pose_rotation, 4),
            float_values_marker(&self.lens_intrinsic_calibration, 5),
            lens_pose_reference_marker(self.lens_pose_reference),
            i32_values_marker(&self.pre_correction_active_array, 4),
            fps_ranges_marker(&self.ae_fps_ranges),
            bool_token(
                self.request_keys
                    .contains(&(ACAMERA_CONTROL_AE_TARGET_FPS_RANGE as i32))
            ),
            u8_modes_marker(&self.noise_reduction_modes),
            bool_token(
                self.request_keys
                    .contains(&(ACAMERA_NOISE_REDUCTION_MODE as i32))
            ),
            u8_modes_marker(&self.edge_modes),
            bool_token(self.request_keys.contains(&(ACAMERA_EDGE_MODE as i32))),
        )
    }
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
    timestamp_source: CameraTimestampSource,
    frame_counter: AtomicU64,
    import_counter: AtomicU64,
    previous_timestamp_ns: AtomicI64,
    previous_callback_boottime_ns: AtomicI64,
    last_camera_sync_mode: AtomicU32,
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

    pub(crate) fn published_frame_counts(&self) -> (u64, u64) {
        let left = self
            .left
            .as_ref()
            .map(CameraProbeStream::published_frame_count)
            .unwrap_or(0);
        let right = self
            .right
            .as_ref()
            .map(CameraProbeStream::published_frame_count)
            .unwrap_or(left);
        (left, right)
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
        let static_metadata = load_camera_static_metadata(manager, camera_id_c.as_ptr());
        let selected_size = select_private_size(&static_metadata.private_output_sizes);

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
        let latency_launch_settings = current_camera_latency_settings();
        let capture_fps_status = apply_camera_latency_capture_fps(
            capture_request,
            &static_metadata,
            if latency_launch_settings.enabled {
                latency_launch_settings.capture_fps.requested_fps()
            } else {
                None
            },
        );
        let capture_processing_status = apply_camera_latency_capture_processing(
            capture_request,
            &static_metadata,
            if latency_launch_settings.enabled {
                latency_launch_settings.capture_processing
            } else {
                CameraLatencyCaptureProcessing::TemplateDefault
            },
        );

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
            timestamp_source: static_metadata.timestamp_source,
            frame_counter: AtomicU64::new(0),
            import_counter: AtomicU64::new(0),
            previous_timestamp_ns: AtomicI64::new(0),
            previous_callback_boottime_ns: AtomicI64::new(0),
            last_camera_sync_mode: AtomicU32::new(u32::MAX),
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

        let camera_latency_calibration_available = update_camera_latency_camera_calibration(
            side_label,
            camera_id,
            &static_metadata.lens_pose_rotation,
            &static_metadata.lens_intrinsic_calibration,
            static_metadata.lens_pose_reference,
            &static_metadata.pre_correction_active_array,
            selected_size,
        );

        log_marker(format!(
            "status=camera-stream-started side={} cameraId={} selectedPrivateSize={}x{} readerMaxImages={} repeatingSequenceId={} privateOutputSizes={} cameraLatencyCalibrationAvailable={} cameraLatencyCalibrationAuthority={} {} {} {} {}",
            side_label,
            marker_token(camera_id),
            selected_size[0],
            selected_size[1],
            reader_max_images,
            sequence_id,
            marker_token(&private_sizes_marker(&static_metadata.private_output_sizes)),
            bool_token(camera_latency_calibration_available),
            if camera_latency_calibration_available {
                "android-camera2-static-characteristics-per-eye"
            } else {
                "unavailable"
            },
            capture_fps_status,
            capture_processing_status,
            static_metadata.marker_fields(),
            latency_launch_settings.marker_fields(),
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

    fn published_frame_count(&self) -> u64 {
        if self.context_raw.is_null() {
            0
        } else {
            unsafe { (*self.context_raw).frame_counter.load(Ordering::Acquire) }
        }
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
    let callback_boottime_ns = boottime_now_ns();
    let previous_timestamp_ns = context
        .previous_timestamp_ns
        .swap(timestamp_ns, Ordering::AcqRel);
    let source_delta_ns = if timestamp_ns > 0 && previous_timestamp_ns > 0 {
        Some(timestamp_ns.abs_diff(previous_timestamp_ns))
    } else {
        None
    };
    let previous_callback_boottime_ns = context
        .previous_callback_boottime_ns
        .swap(callback_boottime_ns, Ordering::AcqRel);
    let callback_delta_ns = if callback_boottime_ns > 0 && previous_callback_boottime_ns > 0 {
        Some(callback_boottime_ns.abs_diff(previous_callback_boottime_ns))
    } else {
        None
    };
    let callback_age_ns = if context.timestamp_source == CameraTimestampSource::Realtime
        && timestamp_ns > 0
        && callback_boottime_ns >= timestamp_ns
    {
        Some((callback_boottime_ns - timestamp_ns) as u64)
    } else {
        None
    };
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
    let latency_settings = current_camera_latency_settings();
    let capture_viewer_basis =
        camera_latency_capture_viewer_basis(timestamp_ns, callback_boottime_ns);
    let camera_sync_active = if latency_settings.enabled {
        latency_settings.camera_sync_mode
    } else {
        CameraLatencyCameraSyncMode::EarlyDeleteAhbRetained
    };
    let hold_image_until_gpu_fence =
        camera_sync_active == CameraLatencyCameraSyncMode::HoldImageUntilGpuFence;
    let camera_sync_changed = context
        .last_camera_sync_mode
        .swap(camera_sync_active as u32, Ordering::AcqRel)
        != camera_sync_active as u32;
    let log_frame =
        frame_index <= 4 || camera_latency_per_frame_log_enabled() || camera_sync_changed;
    if log_frame {
        log_marker(format!(
            "status=camera-frame-acquired side={} cameraId={} frameIndex={} hardwareBufferId={} timestampNs={} sourceDeltaMs={} callbackBoottimeNs={} callbackDeltaMs={} callbackAgeMs={} sensorTimestampSource={} capturePoseAssociation={} capturePoseSelection={} capturePoseTargetTimestampNs={} capturePosePreviousTimestampNs={} capturePoseNextTimestampNs={} capturePoseInterpolationFraction={:.5} capturePoseAvailable={} width={} height={} format={} usage=0x{:x} stride={} hwbImportSequence={} imageFormat=PRIVATE usageFlag=GPU_SAMPLED_IMAGE perFrameLogEnabled={} cameraSyncTransition={} cameraSyncActive={} imageReleaseApi={} imageLeaseActive={} producerConsumerSync={}",
            context.side_label,
            marker_token(&context.camera_id),
            frame_index,
            descriptor.hardware_buffer_id,
            timestamp_ns,
            optional_ns_ms(source_delta_ns),
            callback_boottime_ns,
            optional_ns_ms(callback_delta_ns),
            optional_ns_ms(callback_age_ns),
            context.timestamp_source.marker_token(),
            capture_viewer_basis.association,
            capture_viewer_basis.pose_selection,
            capture_viewer_basis.target_timestamp_ns,
            capture_viewer_basis.previous_timestamp_ns,
            capture_viewer_basis.next_timestamp_ns,
            capture_viewer_basis.interpolation_fraction,
            bool_token(capture_viewer_basis.basis.is_some()),
            descriptor.width,
            descriptor.height,
            descriptor.format,
            descriptor.usage,
            descriptor.stride,
            hwb_import_sequence,
            bool_token(camera_latency_per_frame_log_enabled()),
            bool_token(camera_sync_changed),
            camera_sync_active.marker_token(),
            if hold_image_until_gpu_fence {
                "AImage_delete-on-frame-lease-drop"
            } else {
                "AImage_delete-after-publish"
            },
            bool_token(hold_image_until_gpu_fence),
            if hold_image_until_gpu_fence {
                "image-slot-held-through-vulkan-frame-fence"
            } else {
                "ahb-handle-retained-without-producer-release-fence"
            },
        ));
    }

    let image_lease = if hold_image_until_gpu_fence {
        Some(CameraProbeImageLease::new(
            image,
            context.side_label,
            &context.camera_id,
            frame_index,
            descriptor.hardware_buffer_id,
            log_frame,
        ))
    } else {
        None
    };

    let mut guard = match context.latest_frame.lock() {
        Ok(guard) => guard,
        Err(_) => {
            if !hold_image_until_gpu_fence {
                AImage_delete(image);
            }
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
        timestamp_source: context.timestamp_source,
        callback_age_ns,
        callback_boottime_ns,
        source_delta_ns,
        callback_delta_ns,
        capture_viewer_basis: capture_viewer_basis.basis,
        image_lease,
    });
    context.frame_available.notify_all();
    if !hold_image_until_gpu_fence {
        AImage_delete(image);
    }
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

unsafe fn load_camera_static_metadata(
    manager: *mut ACameraManager,
    camera_id: *const std::os::raw::c_char,
) -> CameraProbeStaticMetadata {
    let mut metadata: *mut ACameraMetadata = ptr::null_mut();
    if ACameraManager_getCameraCharacteristics(manager, camera_id, &mut metadata) != 0
        || metadata.is_null()
    {
        return CameraProbeStaticMetadata {
            private_output_sizes: Vec::new(),
            timestamp_source: CameraTimestampSource::Unavailable,
            lens_pose_translation: Vec::new(),
            lens_pose_rotation: Vec::new(),
            lens_intrinsic_calibration: Vec::new(),
            lens_pose_reference: None,
            pre_correction_active_array: Vec::new(),
            ae_fps_ranges: Vec::new(),
            noise_reduction_modes: Vec::new(),
            edge_modes: Vec::new(),
            request_keys: Vec::new(),
        };
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
    let timestamp_source = match read_u8_values(metadata, ACAMERA_SENSOR_INFO_TIMESTAMP_SOURCE)
        .first()
        .copied()
    {
        Some(1) => CameraTimestampSource::Realtime,
        Some(_) => CameraTimestampSource::Unknown,
        None => CameraTimestampSource::Unavailable,
    };
    let lens_pose_translation = read_f32_values(metadata, ACAMERA_LENS_POSE_TRANSLATION);
    let lens_pose_rotation = read_f32_values(metadata, ACAMERA_LENS_POSE_ROTATION);
    let lens_intrinsic_calibration = read_f32_values(metadata, ACAMERA_LENS_INTRINSIC_CALIBRATION);
    let lens_pose_reference = read_u8_values(metadata, ACAMERA_LENS_POSE_REFERENCE)
        .first()
        .copied();
    let pre_correction_active_array = read_i32_values(
        metadata,
        ACAMERA_SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
    );
    let ae_fps_ranges = read_i32_values(metadata, ACAMERA_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        .chunks_exact(2)
        .map(|range| [range[0], range[1]])
        .collect();
    let noise_reduction_modes = read_u8_values(
        metadata,
        ACAMERA_NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
    );
    let edge_modes = read_u8_values(metadata, ACAMERA_EDGE_AVAILABLE_EDGE_MODES);
    let request_keys = read_i32_values(metadata, ACAMERA_REQUEST_AVAILABLE_REQUEST_KEYS);
    ACameraMetadata_free(metadata);
    CameraProbeStaticMetadata {
        private_output_sizes: sizes,
        timestamp_source,
        lens_pose_translation,
        lens_pose_rotation,
        lens_intrinsic_calibration,
        lens_pose_reference,
        pre_correction_active_array,
        ae_fps_ranges,
        noise_reduction_modes,
        edge_modes,
        request_keys,
    }
}

unsafe fn apply_camera_latency_capture_processing(
    capture_request: *mut ACaptureRequest,
    metadata: &CameraProbeStaticMetadata,
    requested: CameraLatencyCaptureProcessing,
) -> String {
    if requested == CameraLatencyCaptureProcessing::TemplateDefault {
        return "captureProcessingRequested=template-default noiseReductionSelected=default noiseReductionApplyStatus=not-requested edgeSelected=default edgeApplyStatus=not-requested captureProcessingApplyStatus=not-requested".to_string();
    }

    let noise_request_key = metadata
        .request_keys
        .contains(&(ACAMERA_NOISE_REDUCTION_MODE as i32));
    let noise_mode_supported = metadata
        .noise_reduction_modes
        .contains(&ACAMERA_NOISE_REDUCTION_MODE_OFF);
    let noise_status = if !noise_request_key {
        "request-key-unsupported".to_string()
    } else if !noise_mode_supported {
        "mode-unsupported".to_string()
    } else {
        let value = [ACAMERA_NOISE_REDUCTION_MODE_OFF];
        let status = ACaptureRequest_setEntry_u8(
            capture_request,
            ACAMERA_NOISE_REDUCTION_MODE,
            1,
            value.as_ptr(),
        );
        if status == 0 {
            "set-supported".to_string()
        } else {
            format!("set-error-{status}")
        }
    };

    let edge_request_key = metadata.request_keys.contains(&(ACAMERA_EDGE_MODE as i32));
    let edge_mode_supported = metadata.edge_modes.contains(&ACAMERA_EDGE_MODE_OFF);
    let edge_status = if !edge_request_key {
        "request-key-unsupported".to_string()
    } else if !edge_mode_supported {
        "mode-unsupported".to_string()
    } else {
        let value = [ACAMERA_EDGE_MODE_OFF];
        let status =
            ACaptureRequest_setEntry_u8(capture_request, ACAMERA_EDGE_MODE, 1, value.as_ptr());
        if status == 0 {
            "set-supported".to_string()
        } else {
            format!("set-error-{status}")
        }
    };

    let apply_status = if noise_status == "set-supported" && edge_status == "set-supported" {
        "set-both-supported"
    } else {
        "partial-or-unsupported"
    };
    format!(
        "captureProcessingRequested={} noiseReductionAvailableModes={} noiseReductionSelected={} noiseReductionApplyStatus={} edgeAvailableModes={} edgeSelected={} edgeApplyStatus={} captureProcessingApplyStatus={} supportGated=true fallbackApplied=false",
        requested.marker_token(),
        u8_modes_marker(&metadata.noise_reduction_modes),
        if noise_mode_supported { "OFF" } else { "unavailable" },
        noise_status,
        u8_modes_marker(&metadata.edge_modes),
        if edge_mode_supported { "OFF" } else { "unavailable" },
        edge_status,
        apply_status,
    )
}

unsafe fn apply_camera_latency_capture_fps(
    capture_request: *mut ACaptureRequest,
    metadata: &CameraProbeStaticMetadata,
    requested_fps: Option<i32>,
) -> String {
    let Some(requested_fps) = requested_fps else {
        return "captureFpsRequested=camera-default captureFpsSelected=default captureFpsApplyStatus=not-requested".to_string();
    };
    if !metadata
        .request_keys
        .contains(&(ACAMERA_CONTROL_AE_TARGET_FPS_RANGE as i32))
    {
        return format!(
            "captureFpsRequested={} captureFpsSelected=unavailable captureFpsApplyStatus=request-key-unsupported",
            requested_fps
        );
    }
    let exact = [requested_fps, requested_fps];
    if !metadata.ae_fps_ranges.contains(&exact) {
        return format!(
            "captureFpsRequested={} captureFpsSelected=unavailable captureFpsApplyStatus=exact-fixed-range-unsupported",
            requested_fps
        );
    }
    let status = ACaptureRequest_setEntry_i32(
        capture_request,
        ACAMERA_CONTROL_AE_TARGET_FPS_RANGE,
        2,
        exact.as_ptr(),
    );
    format!(
        "captureFpsRequested={} captureFpsSelected={};{} captureFpsApplyStatus={}",
        requested_fps,
        exact[0],
        exact[1],
        if status == 0 {
            "set-exact-supported".to_string()
        } else {
            format!("set-error-{status}")
        }
    )
}

fn fps_ranges_marker(ranges: &[[i32; 2]]) -> String {
    if ranges.is_empty() {
        return "unavailable".to_string();
    }
    ranges
        .iter()
        .map(|range| format!("{}-{}", range[0], range[1]))
        .collect::<Vec<_>>()
        .join(";")
}

fn u8_modes_marker(modes: &[u8]) -> String {
    if modes.is_empty() {
        "unavailable".to_string()
    } else {
        modes
            .iter()
            .map(u8::to_string)
            .collect::<Vec<_>>()
            .join(";")
    }
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

unsafe fn read_u8_values(metadata: *const ACameraMetadata, tag: u32) -> Vec<u8> {
    let Some(entry) = metadata_entry(metadata, tag) else {
        return Vec::new();
    };
    if entry.count == 0 || entry.data.u8_.is_null() {
        return Vec::new();
    }
    std::slice::from_raw_parts(entry.data.u8_, entry.count as usize).to_vec()
}

unsafe fn read_f32_values(metadata: *const ACameraMetadata, tag: u32) -> Vec<f32> {
    let Some(entry) = metadata_entry(metadata, tag) else {
        return Vec::new();
    };
    if entry.count == 0 || entry.data.f_.is_null() {
        return Vec::new();
    }
    std::slice::from_raw_parts(entry.data.f_, entry.count as usize).to_vec()
}

fn float_values_marker(values: &[f32], expected_count: usize) -> String {
    if values.len() < expected_count {
        return "unavailable".to_string();
    }
    values
        .iter()
        .take(expected_count)
        .map(|value| format!("{value:.6}"))
        .collect::<Vec<_>>()
        .join(";")
}

fn i32_values_marker(values: &[i32], expected_count: usize) -> String {
    if values.len() < expected_count {
        return "unavailable".to_string();
    }
    values
        .iter()
        .take(expected_count)
        .map(i32::to_string)
        .collect::<Vec<_>>()
        .join(";")
}

fn lens_pose_reference_marker(value: Option<u8>) -> &'static str {
    match value {
        Some(0) => "primary-camera",
        Some(1) => "gyroscope",
        Some(2) => "undefined",
        Some(3) => "automotive",
        Some(_) => "unknown-value",
        None => "unavailable",
    }
}

fn optional_ns_ms(value: Option<u64>) -> String {
    value
        .map(|value| format!("{:.3}", value as f64 / 1_000_000.0))
        .unwrap_or_else(|| "unavailable".to_string())
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
