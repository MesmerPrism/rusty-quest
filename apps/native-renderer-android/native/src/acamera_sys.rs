//! Minimal Android NDK camera and image-reader FFI for the public HWB route.
//!
//! This file contains only public Camera2/HWB acquisition bindings; downstream
//! visual-effect implementations are intentionally excluded.

#![allow(non_camel_case_types)]
#![allow(dead_code)]
#![allow(non_snake_case)]

use std::os::raw::{c_char, c_int, c_uint, c_void};

use jni::sys::{jobject, JNIEnv};

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ACameraManager {
    _unused: [u8; 0],
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ACameraIdList {
    pub numCameras: c_int,
    pub cameraIds: *mut *const c_char,
}

pub const AIMAGE_FORMAT_PRIVATE: u32 = 0x22;
pub const AIMAGE_FORMAT_RGBA_8888: u32 = 0x1;
pub const AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN: u64 = 3;
pub const AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE: u64 = 1 << 8;
pub const AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER: u64 = 1 << 9;
pub const AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT: u64 = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;

pub const ACAMERA_CONTROL_AE_TARGET_FPS_RANGE: u32 = (ACAMERA_CONTROL << 16) + 5;
pub const ACAMERA_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES: u32 = (ACAMERA_CONTROL << 16) + 20;
pub const ACAMERA_CONTROL_AE_STATE: u32 = (ACAMERA_CONTROL << 16) + 31;
pub const ACAMERA_CONTROL_AWB_STATE: u32 = (ACAMERA_CONTROL << 16) + 34;
pub const ACAMERA_EDGE_MODE: u32 = ACAMERA_EDGE << 16;
pub const ACAMERA_EDGE_AVAILABLE_EDGE_MODES: u32 = (ACAMERA_EDGE << 16) + 2;
pub const ACAMERA_NOISE_REDUCTION_MODE: u32 = ACAMERA_NOISE_REDUCTION << 16;
pub const ACAMERA_NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES: u32 =
    (ACAMERA_NOISE_REDUCTION << 16) + 2;
pub const ACAMERA_REQUEST_AVAILABLE_CAPABILITIES: u32 = (ACAMERA_REQUEST << 16) + 12;
pub const ACAMERA_REQUEST_AVAILABLE_REQUEST_KEYS: u32 = (ACAMERA_REQUEST << 16) + 13;
pub const ACAMERA_REQUEST_AVAILABLE_RESULT_KEYS: u32 = (ACAMERA_REQUEST << 16) + 14;
pub const ACAMERA_LENS_POSE_ROTATION: u32 = (ACAMERA_LENS << 16) + 6;
pub const ACAMERA_LENS_POSE_TRANSLATION: u32 = (ACAMERA_LENS << 16) + 7;
pub const ACAMERA_LENS_INTRINSIC_CALIBRATION: u32 = (ACAMERA_LENS << 16) + 10;
pub const ACAMERA_LENS_POSE_REFERENCE: u32 = (ACAMERA_LENS << 16) + 12;
pub const ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS: u32 = (ACAMERA_SCALER << 16) + 11;
pub const ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS: u32 = (ACAMERA_SCALER << 16) + 10;
pub const ACAMERA_SENSOR_EXPOSURE_TIME: u32 = ACAMERA_SENSOR << 16;
pub const ACAMERA_SENSOR_FRAME_DURATION: u32 = (ACAMERA_SENSOR << 16) + 1;
pub const ACAMERA_SENSOR_SENSITIVITY: u32 = (ACAMERA_SENSOR << 16) + 2;
pub const ACAMERA_SENSOR_TIMESTAMP: u32 = (ACAMERA_SENSOR << 16) + 16;
pub const ACAMERA_SENSOR_INFO_TIMESTAMP_SOURCE: u32 = (ACAMERA_SENSOR_INFO << 16) + 8;
pub const ACAMERA_SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE: u32 =
    (ACAMERA_SENSOR_INFO << 16) + 10;
pub const ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL: u32 = ACAMERA_INFO << 16;
pub const ACAMERA_SYNC_FRAME_NUMBER: u32 = ACAMERA_SYNC << 16;

pub const ACAMERA_CONTROL_AE_STATE_INACTIVE: u8 = 0;
pub const ACAMERA_CONTROL_AE_STATE_SEARCHING: u8 = 1;
pub const ACAMERA_CONTROL_AE_STATE_CONVERGED: u8 = 2;
pub const ACAMERA_CONTROL_AE_STATE_LOCKED: u8 = 3;
pub const ACAMERA_CONTROL_AE_STATE_FLASH_REQUIRED: u8 = 4;
pub const ACAMERA_CONTROL_AE_STATE_PRECAPTURE: u8 = 5;
pub const ACAMERA_CONTROL_AWB_STATE_INACTIVE: u8 = 0;
pub const ACAMERA_CONTROL_AWB_STATE_SEARCHING: u8 = 1;
pub const ACAMERA_CONTROL_AWB_STATE_CONVERGED: u8 = 2;
pub const ACAMERA_CONTROL_AWB_STATE_LOCKED: u8 = 3;
pub const ACAMERA_EDGE_MODE_OFF: u8 = 0;
pub const ACAMERA_EDGE_MODE_FAST: u8 = 1;
pub const ACAMERA_EDGE_MODE_HIGH_QUALITY: u8 = 2;
pub const ACAMERA_NOISE_REDUCTION_MODE_OFF: u8 = 0;
pub const ACAMERA_NOISE_REDUCTION_MODE_FAST: u8 = 1;
pub const ACAMERA_NOISE_REDUCTION_MODE_HIGH_QUALITY: u8 = 2;

const ACAMERA_CONTROL: u32 = 1;
const ACAMERA_EDGE: u32 = 3;
const ACAMERA_LENS: u32 = 8;
const ACAMERA_NOISE_REDUCTION: u32 = 10;
const ACAMERA_REQUEST: u32 = 12;
const ACAMERA_SCALER: u32 = 13;
const ACAMERA_SENSOR: u32 = 14;
const ACAMERA_SENSOR_INFO: u32 = 15;
const ACAMERA_INFO: u32 = 21;
const ACAMERA_SYNC: u32 = 23;

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ACameraDevice {
    _unused: [u8; 0],
}

pub type ACameraDevice_StateCallback =
    Option<unsafe extern "C" fn(context: *mut c_void, device: *mut ACameraDevice)>;

pub type ACameraDevice_ErrorStateCallback =
    Option<unsafe extern "C" fn(context: *mut c_void, device: *mut ACameraDevice, error: c_int)>;

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ACameraDevice_StateCallbacks {
    pub context: *mut c_void,
    pub onDisconnected: ACameraDevice_StateCallback,
    pub onError: ACameraDevice_ErrorStateCallback,
}

pub type ACameraDevice_request_template = c_uint;
pub const TEMPLATE_PREVIEW: ACameraDevice_request_template = 1;
pub const TEMPLATE_RECORD: ACameraDevice_request_template = 3;

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ACaptureRequest {
    _unused: [u8; 0],
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ACameraMetadata {
    _unused: [u8; 0],
}

#[repr(C)]
#[derive(Copy, Clone)]
pub union ACameraMetadataConstEntryData {
    pub u8_: *const u8,
    pub i32_: *const i32,
    pub f_: *const f32,
    pub i64_: *const i64,
    pub d_: *const f64,
    pub r_: *const ACameraMetadataRational,
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ACameraMetadataRational {
    pub numerator: i32,
    pub denominator: i32,
}

#[repr(C)]
#[derive(Copy, Clone)]
pub struct ACameraMetadataConstEntry {
    pub tag: u32,
    pub type_: u8,
    pub count: u32,
    pub data: ACameraMetadataConstEntryData,
}

pub type ANativeWindow = ndk_sys::ANativeWindow;
pub type AHardwareBuffer = ndk_sys::AHardwareBuffer;

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ACaptureSessionOutput {
    _unused: [u8; 0],
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ACaptureSessionOutputContainer {
    _unused: [u8; 0],
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct AImageReader {
    _unused: [u8; 0],
}

pub type camera_status_t = c_int;
pub type media_status_t = c_int;
pub type ACameraWindowType = ANativeWindow;

pub type AImageReader_ImageCallback =
    Option<unsafe extern "C" fn(context: *mut c_void, reader: *mut AImageReader)>;

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct AImageReader_ImageListener {
    pub context: *mut c_void,
    pub onImageAvailable: AImageReader_ImageCallback,
}

pub type AImageReader_BufferRemovedCallback = Option<
    unsafe extern "C" fn(
        context: *mut c_void,
        reader: *mut AImageReader,
        buffer: *mut AHardwareBuffer,
    ),
>;

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct AImageReader_BufferRemovedListener {
    pub context: *mut c_void,
    pub onBufferRemoved: AImageReader_BufferRemovedCallback,
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ACameraOutputTarget {
    _unused: [u8; 0],
}

pub type ACameraCaptureSession_stateCallback =
    Option<unsafe extern "C" fn(context: *mut c_void, session: *mut ACameraCaptureSession)>;

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ACameraCaptureSession_stateCallbacks {
    pub context: *mut c_void,
    pub onClosed: ACameraCaptureSession_stateCallback,
    pub onReady: ACameraCaptureSession_stateCallback,
    pub onActive: ACameraCaptureSession_stateCallback,
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ACameraCaptureSession {
    _unused: [u8; 0],
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct AImage {
    _unused: [u8; 0],
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ACameraCaptureFailure {
    pub frameNumber: i64,
    pub reason: c_int,
    pub sequenceId: c_int,
    pub wasImageCaptured: bool,
}

pub type ACameraCaptureSession_captureCallback_start = Option<
    unsafe extern "C" fn(
        context: *mut c_void,
        session: *mut ACameraCaptureSession,
        request: *const ACaptureRequest,
        timestamp: i64,
    ),
>;
pub type ACameraCaptureSession_captureCallback_result = Option<
    unsafe extern "C" fn(
        context: *mut c_void,
        session: *mut ACameraCaptureSession,
        request: *mut ACaptureRequest,
        result: *const ACameraMetadata,
    ),
>;
pub type ACameraCaptureSession_captureCallback_failed = Option<
    unsafe extern "C" fn(
        context: *mut c_void,
        session: *mut ACameraCaptureSession,
        request: *mut ACaptureRequest,
        failure: *mut ACameraCaptureFailure,
    ),
>;
pub type ACameraCaptureSession_captureCallback_sequenceEnd = Option<
    unsafe extern "C" fn(
        context: *mut c_void,
        session: *mut ACameraCaptureSession,
        sequenceId: c_int,
        frameNumber: i64,
    ),
>;
pub type ACameraCaptureSession_captureCallback_sequenceAbort = Option<
    unsafe extern "C" fn(
        context: *mut c_void,
        session: *mut ACameraCaptureSession,
        sequenceId: c_int,
    ),
>;
pub type ACameraCaptureSession_captureCallback_bufferLost = Option<
    unsafe extern "C" fn(
        context: *mut c_void,
        session: *mut ACameraCaptureSession,
        request: *mut ACaptureRequest,
        window: *mut ACameraWindowType,
        frameNumber: i64,
    ),
>;

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ACameraCaptureSession_captureCallbacks {
    pub context: *mut c_void,
    pub onCaptureStarted: ACameraCaptureSession_captureCallback_start,
    pub onCaptureProgressed: ACameraCaptureSession_captureCallback_result,
    pub onCaptureCompleted: ACameraCaptureSession_captureCallback_result,
    pub onCaptureFailed: ACameraCaptureSession_captureCallback_failed,
    pub onCaptureSequenceCompleted: ACameraCaptureSession_captureCallback_sequenceEnd,
    pub onCaptureSequenceAborted: ACameraCaptureSession_captureCallback_sequenceAbort,
    pub onCaptureBufferLost: ACameraCaptureSession_captureCallback_bufferLost,
}

#[link(name = "nativewindow")]
extern "C" {
    pub fn ANativeWindow_acquire(window: *mut ANativeWindow);
    pub fn ANativeWindow_release(window: *mut ANativeWindow);
    pub fn ANativeWindow_toSurface(env: *mut JNIEnv, window: *mut ANativeWindow) -> jobject;
}

#[link(name = "mediandk")]
extern "C" {
    pub fn AImageReader_newWithUsage(
        width: i32,
        height: i32,
        format: u32,
        usage: u64,
        maxImages: i32,
        reader: *mut *mut AImageReader,
    ) -> media_status_t;

    pub fn AImageReader_setImageListener(
        reader: *mut AImageReader,
        listener: *mut AImageReader_ImageListener,
    ) -> media_status_t;

    pub fn AImageReader_getWindow(
        reader: *mut AImageReader,
        window: *mut *mut ANativeWindow,
    ) -> media_status_t;

    pub fn AImageReader_delete(reader: *mut AImageReader);

    pub fn AImageReader_acquireLatestImage(
        reader: *mut AImageReader,
        image: *mut *mut AImage,
    ) -> media_status_t;

    pub fn AImageReader_acquireLatestImageAsync(
        reader: *mut AImageReader,
        image: *mut *mut AImage,
        acquireFenceFd: *mut c_int,
    ) -> media_status_t;

    pub fn AImageReader_setBufferRemovedListener(
        reader: *mut AImageReader,
        listener: *mut AImageReader_BufferRemovedListener,
    ) -> media_status_t;

    pub fn AImage_getTimestamp(image: *const AImage, timestampNs: *mut i64) -> media_status_t;

    pub fn AImage_getHardwareBuffer(
        image: *const AImage,
        buffer: *mut *mut AHardwareBuffer,
    ) -> media_status_t;

    pub fn AImage_delete(image: *mut AImage);

    pub fn AImage_deleteAsync(image: *mut AImage, releaseFenceFd: c_int);
}

#[link(name = "dl")]
extern "C" {
    pub fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
}

#[link(name = "c")]
extern "C" {
    pub fn close(fd: c_int) -> c_int;
}

#[link(name = "camera2ndk")]
extern "C" {
    pub fn ACameraManager_create() -> *mut ACameraManager;
    pub fn ACameraManager_delete(manager: *mut ACameraManager);
    pub fn ACameraManager_getCameraIdList(
        manager: *mut ACameraManager,
        cameraIdList: *mut *mut ACameraIdList,
    ) -> camera_status_t;
    pub fn ACameraManager_deleteCameraIdList(cameraIdList: *mut ACameraIdList);
    pub fn ACameraManager_getCameraCharacteristics(
        manager: *mut ACameraManager,
        cameraId: *const c_char,
        characteristics: *mut *mut ACameraMetadata,
    ) -> camera_status_t;
    pub fn ACameraMetadata_free(metadata: *mut ACameraMetadata);
    pub fn ACameraMetadata_getConstEntry(
        metadata: *const ACameraMetadata,
        tag: u32,
        entry: *mut ACameraMetadataConstEntry,
    ) -> camera_status_t;

    pub fn ACameraManager_openCamera(
        manager: *mut ACameraManager,
        cameraId: *const c_char,
        callback: *mut ACameraDevice_StateCallbacks,
        device: *mut *mut ACameraDevice,
    ) -> camera_status_t;

    pub fn ACameraDevice_createCaptureRequest(
        device: *const ACameraDevice,
        templateId: ACameraDevice_request_template,
        request: *mut *mut ACaptureRequest,
    ) -> camera_status_t;

    pub fn ACaptureSessionOutput_create(
        anw: *mut ACameraWindowType,
        output: *mut *mut ACaptureSessionOutput,
    ) -> camera_status_t;
    pub fn ACaptureSessionOutputContainer_create(
        container: *mut *mut ACaptureSessionOutputContainer,
    ) -> camera_status_t;
    pub fn ACaptureSessionOutputContainer_add(
        container: *mut ACaptureSessionOutputContainer,
        output: *const ACaptureSessionOutput,
    ) -> camera_status_t;
    pub fn ACaptureSessionOutputContainer_free(container: *mut ACaptureSessionOutputContainer);
    pub fn ACaptureSessionOutput_free(output: *mut ACaptureSessionOutput);

    pub fn ACameraOutputTarget_create(
        window: *mut ACameraWindowType,
        output: *mut *mut ACameraOutputTarget,
    ) -> camera_status_t;
    pub fn ACameraOutputTarget_free(output: *mut ACameraOutputTarget);
    pub fn ACaptureRequest_addTarget(
        request: *mut ACaptureRequest,
        output: *const ACameraOutputTarget,
    ) -> camera_status_t;
    pub fn ACaptureRequest_setEntry_u8(
        request: *mut ACaptureRequest,
        tag: u32,
        count: c_uint,
        data: *const u8,
    ) -> camera_status_t;
    pub fn ACaptureRequest_setEntry_i32(
        request: *mut ACaptureRequest,
        tag: u32,
        count: c_uint,
        data: *const i32,
    ) -> camera_status_t;
    pub fn ACaptureRequest_setEntry_i64(
        request: *mut ACaptureRequest,
        tag: u32,
        count: c_uint,
        data: *const i64,
    ) -> camera_status_t;
    pub fn ACaptureRequest_removeTarget(
        request: *mut ACaptureRequest,
        output: *const ACameraOutputTarget,
    ) -> camera_status_t;

    pub fn ACameraDevice_createCaptureSession(
        device: *mut ACameraDevice,
        outputs: *const ACaptureSessionOutputContainer,
        callbacks: *const ACameraCaptureSession_stateCallbacks,
        session: *mut *mut ACameraCaptureSession,
    ) -> camera_status_t;
    pub fn ACameraCaptureSession_setRepeatingRequest(
        session: *mut ACameraCaptureSession,
        callbacks: *mut ACameraCaptureSession_captureCallbacks,
        numRequests: c_int,
        requests: *mut *mut ACaptureRequest,
        captureSequenceId: *mut c_int,
    ) -> camera_status_t;
    pub fn ACameraCaptureSession_stopRepeating(
        session: *mut ACameraCaptureSession,
    ) -> camera_status_t;
    pub fn ACameraCaptureSession_close(session: *mut ACameraCaptureSession);
    pub fn ACameraDevice_close(device: *mut ACameraDevice) -> camera_status_t;
    pub fn ACaptureRequest_free(request: *mut ACaptureRequest);
}
