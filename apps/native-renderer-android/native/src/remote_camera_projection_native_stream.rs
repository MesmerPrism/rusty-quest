//! Native Android broker-camera projection source.
//!
//! Java owns the RMANVID1 socket reader and MediaCodec control. Rust owns the
//! two `AImageReader` surfaces and publishes decoded left/right
//! `AHardwareBuffer` frames as the same stereo frame type used by direct
//! Camera2.

use std::{
    ptr,
    sync::{
        atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering},
        Mutex,
    },
};

use jni::sys::{jclass, jint, jlong, jobject, JNIEnv};

use crate::{
    acamera_sys::{
        AImage, AImageReader, AImageReader_BufferRemovedListener, AImageReader_ImageListener,
        AImageReader_acquireLatestImage, AImageReader_delete, AImageReader_getWindow,
        AImageReader_newWithUsage, AImageReader_setBufferRemovedListener,
        AImageReader_setImageListener, AImage_delete, AImage_getHardwareBuffer,
        AImage_getTimestamp, ANativeWindow, ANativeWindow_acquire, ANativeWindow_release,
        ANativeWindow_toSurface, AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT,
        AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, AIMAGE_FORMAT_PRIVATE,
    },
    android_hardware_buffer::AndroidHardwareBufferHandle,
    camera_projection_metadata::CameraProjectionSourceLayout,
    marker,
    native_camera::{NativeCameraFrame, NativeStereoCameraFrame},
    native_camera_metadata::NativeCameraCaptureResultCorrelation,
};

static REMOTE_STREAM: Mutex<RemoteCameraBrokerStream> = Mutex::new(RemoteCameraBrokerStream {
    left: None,
    right: None,
    packed: None,
});
static REMOTE_LATEST_LEFT: Mutex<Option<NativeCameraFrame>> = Mutex::new(None);
static REMOTE_LATEST_RIGHT: Mutex<Option<NativeCameraFrame>> = Mutex::new(None);
static REMOTE_LATEST_QUERY_COUNT: AtomicU64 = AtomicU64::new(0);
static REMOTE_PACKED_PAIR_METADATA: Mutex<Option<PackedPairMetadata>> = Mutex::new(None);

const SIDE_LEFT: i32 = 1;
const SIDE_RIGHT: i32 = 2;
const SIDE_STEREO: i32 = 3;

#[derive(Clone, Copy)]
struct PackedPairMetadata {
    pair_id: u64,
    left_source_frame: u64,
    right_source_frame: u64,
    left_sensor_timestamp_ns: i64,
    right_sensor_timestamp_ns: i64,
    pair_delta_ns: u64,
}

const EVENT_START_REQUESTED: i32 = 1;
const EVENT_STOPPED: i32 = 3;
const EVENT_ERROR: i32 = 4;
const EVENT_CONNECTED: i32 = 8;
const EVENT_STREAM_HEADER: i32 = 9;

pub(crate) fn latest_remote_stereo_frame() -> Option<NativeStereoCameraFrame> {
    let query_count = REMOTE_LATEST_QUERY_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
    let should_log = query_count == 1 || query_count % 120 == 0;
    let (left_configured, right_configured, packed_configured) = {
        let stream = REMOTE_STREAM.lock().ok()?;
        (
            stream.left.is_some() || stream.packed.is_some(),
            stream.right.is_some() || stream.packed.is_some(),
            stream.packed.is_some(),
        )
    };
    let left_latest = REMOTE_LATEST_LEFT.lock().ok()?.as_ref().cloned();
    let right_latest = REMOTE_LATEST_RIGHT.lock().ok()?.as_ref().cloned();
    let left_source_frame = left_latest
        .as_ref()
        .map(|frame| frame.source_frame)
        .unwrap_or(0);
    let right_source_frame = right_latest
        .as_ref()
        .map(|frame| frame.source_frame)
        .unwrap_or(0);
    let left_import_sequence = left_latest
        .as_ref()
        .map(|frame| frame.import_sequence)
        .unwrap_or(0);
    let right_import_sequence = right_latest
        .as_ref()
        .map(|frame| frame.import_sequence)
        .unwrap_or(0);
    // Stale inactive lane bookkeeping must not block intentional single-lane
    // diagnostics; the real stereo pair wins as soon as both sides publish.
    let (left, right, pairing_policy) = match (left_latest, right_latest) {
        (Some(left), Some(right)) if packed_configured => {
            (left, right, "remote-broker-packed-sbs-source-timestamp")
        }
        (Some(left), Some(right)) => (left, right, "remote-broker-latest-latest"),
        (Some(left), None) if left_configured => {
            let mut mirrored_right = left.clone();
            mirrored_right.side = "right";
            mirrored_right.camera_id = "remote-broker-left-mirrored-right".to_string();
            (
                left,
                mirrored_right,
                "remote-broker-single-lane-left-mirrored-right",
            )
        }
        (None, Some(right)) if right_configured => {
            let mut mirrored_left = right.clone();
            mirrored_left.side = "left";
            mirrored_left.camera_id = "remote-broker-right-mirrored-left".to_string();
            (
                mirrored_left,
                right,
                "remote-broker-single-lane-right-mirrored-left",
            )
        }
        _ => {
            if should_log {
                marker(
                    "remote-camera-broker-latest",
                    format!(
                        "status=missing queryCount={} leftConfigured={} rightConfigured={} leftLatest={} rightLatest={} leftSourceFrame={} rightSourceFrame={} leftImportSequence={} rightImportSequence={} remoteBrokerCameraProjectionActive=true singleLaneMirrorReady=false stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264",
                        query_count,
                        left_configured,
                        right_configured,
                        left_source_frame > 0,
                        right_source_frame > 0,
                        left_source_frame,
                        right_source_frame,
                        left_import_sequence,
                        right_import_sequence
                    ),
                );
            }
            return None;
        }
    };
    let pair_delta_ns = left.timestamp_ns.abs_diff(right.timestamp_ns);
    if should_log {
        marker(
            "remote-camera-broker-latest",
            format!(
                "status=frame queryCount={} leftConfigured={} rightConfigured={} leftLatest=true rightLatest=true leftSourceFrame={} rightSourceFrame={} leftImportSequence={} rightImportSequence={} stereoPairDeltaNs={} stereoPairingPolicy={} remoteBrokerCameraProjectionActive=true singleLaneMirrorReady={} stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264",
                query_count,
                left_configured,
                right_configured,
                left.source_frame,
                right.source_frame,
                left.import_sequence,
                right.import_sequence,
                pair_delta_ns,
                pairing_policy,
                pairing_policy.starts_with("remote-broker-single-lane-")
            ),
        );
    }
    Some(NativeStereoCameraFrame {
        left,
        right,
        pair_delta_ns,
        pairing_policy,
        source_layout: if packed_configured {
            CameraProjectionSourceLayout::PackedSideBySideLeftRight
        } else {
            CameraProjectionSourceLayout::SeparateEyeTextures
        },
    })
}

struct RemoteCameraBrokerStream {
    left: Option<RemoteCameraLaneStream>,
    right: Option<RemoteCameraLaneStream>,
    packed: Option<RemoteCameraLaneStream>,
}

impl RemoteCameraBrokerStream {
    fn set_lane(&mut self, side: RemoteCameraSide, stream: RemoteCameraLaneStream) {
        match side {
            RemoteCameraSide::Left => self.left = Some(stream),
            RemoteCameraSide::Right => self.right = Some(stream),
            RemoteCameraSide::Packed => self.packed = Some(stream),
        }
    }

    unsafe fn pump_lane(&self, side: RemoteCameraSide) -> i32 {
        let stream = match side {
            RemoteCameraSide::Left => self.left.as_ref(),
            RemoteCameraSide::Right => self.right.as_ref(),
            RemoteCameraSide::Packed => self.packed.as_ref(),
        };
        stream
            .map(|stream| {
                if acquire_remote_camera_image(
                    stream.context.cast(),
                    stream.reader,
                    "java-pump",
                    false,
                ) {
                    1
                } else {
                    0
                }
            })
            .unwrap_or(-1)
    }

    fn stop(&mut self) -> bool {
        let had_stream = self.left.take().is_some()
            || self.right.take().is_some()
            || self.packed.take().is_some();
        if let Ok(mut left) = REMOTE_LATEST_LEFT.lock() {
            *left = None;
        }
        if let Ok(mut right) = REMOTE_LATEST_RIGHT.lock() {
            *right = None;
        }
        if let Ok(mut pair) = REMOTE_PACKED_PAIR_METADATA.lock() {
            *pair = None;
        }
        had_stream
    }
}

struct RemoteCameraLaneStream {
    reader: *mut AImageReader,
    window: *mut ANativeWindow,
    context: *mut RemoteCameraReaderContext,
}

unsafe impl Send for RemoteCameraLaneStream {}

impl Drop for RemoteCameraLaneStream {
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
            if !self.window.is_null() {
                ANativeWindow_release(self.window);
                self.window = ptr::null_mut();
            }
            if !self.reader.is_null() {
                AImageReader_delete(self.reader);
                self.reader = ptr::null_mut();
            }
            if !self.context.is_null() {
                drop(Box::from_raw(self.context));
                self.context = ptr::null_mut();
            }
        }
    }
}

struct RemoteCameraReaderContext {
    alive: AtomicBool,
    side: RemoteCameraSide,
    width: i32,
    height: i32,
    max_images: i32,
    fps_cap: i32,
    frame_count: AtomicU64,
    import_sequence: AtomicU64,
    acquire_errors: AtomicU64,
    dropped_frames: AtomicU64,
    buffer_removed_count: AtomicU64,
    last_accepted_timestamp_ns: AtomicI64,
}

#[derive(Clone, Copy)]
enum RemoteCameraSide {
    Left,
    Right,
    Packed,
}

impl RemoteCameraSide {
    fn from_code(side_code: i32) -> Option<Self> {
        match side_code {
            SIDE_LEFT => Some(Self::Left),
            SIDE_RIGHT => Some(Self::Right),
            SIDE_STEREO => Some(Self::Packed),
            _ => None,
        }
    }

    fn stable_id(self) -> &'static str {
        match self {
            Self::Left => "left",
            Self::Right => "right",
            Self::Packed => "stereo",
        }
    }

    fn camera_id(self) -> &'static str {
        match self {
            Self::Left => "remote-broker-left",
            Self::Right => "remote-broker-right",
            Self::Packed => "remote-broker-packed-stereo",
        }
    }
}

fn clear_latest_for_side(side: RemoteCameraSide) -> bool {
    let target = match side {
        RemoteCameraSide::Left => &REMOTE_LATEST_LEFT,
        RemoteCameraSide::Right => &REMOTE_LATEST_RIGHT,
        RemoteCameraSide::Packed => {
            let left = REMOTE_LATEST_LEFT
                .lock()
                .map(|mut latest| latest.take().is_some())
                .unwrap_or(false);
            let right = REMOTE_LATEST_RIGHT
                .lock()
                .map(|mut latest| latest.take().is_some())
                .unwrap_or(false);
            if let Ok(mut pair) = REMOTE_PACKED_PAIR_METADATA.lock() {
                *pair = None;
            }
            return left || right;
        }
    };
    target
        .lock()
        .map(|mut latest| latest.take().is_some())
        .unwrap_or(false)
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_StereoVideoPlayback_nativeCreateRemoteCameraSurface(
    env: *mut JNIEnv,
    _class: jclass,
    side_code: jint,
    width: jint,
    height: jint,
    max_images: jint,
    fps_cap: jint,
) -> jobject {
    if env.is_null() {
        marker(
            "remote-camera-broker-native-stream",
            "status=error reason=null-jni-env stream=remote_camera_broker_stereo nativeImageReader=true javaHardwareBufferBridge=false",
        );
        return ptr::null_mut();
    }
    let Some(side) = RemoteCameraSide::from_code(side_code) else {
        marker(
            "remote-camera-broker-native-stream",
            format!(
                "status=error reason=invalid-side sideCode={} stream=remote_camera_broker_stereo",
                side_code
            ),
        );
        return ptr::null_mut();
    };

    let width = width.clamp(320, 4096);
    let height = height.clamp(240, 4096);
    let max_images = max_images.clamp(2, 6);
    let fps_cap = fps_cap.clamp(1, 90);

    let mut guard = match REMOTE_STREAM.lock() {
        Ok(guard) => guard,
        Err(_) => {
            marker(
                "remote-camera-broker-native-stream",
                "status=error reason=stream-lock-poisoned stream=remote_camera_broker_stereo",
            );
            return ptr::null_mut();
        }
    };

    match unsafe {
        RemoteCameraLaneStream::create_surface(env, side, width, height, max_images, fps_cap)
    } {
        Ok((stream, surface)) => {
            marker(
                "remote-camera-broker-native-stream",
                format!(
                    "status=surface-created side={} stream=remote_camera_broker_stereo width={} height={} maxImages={} fpsCap={} format=private usage=gpu-sampled-image|gpu-color-output nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false sourceAuthority=manifold-broker-rmanvid1-camera2-h264",
                    side.stable_id(),
                    width,
                    height,
                    max_images,
                    fps_cap
                ),
            );
            guard.set_lane(side, stream);
            surface
        }
        Err(error) => {
            marker(
                "remote-camera-broker-native-stream",
                format!(
                    "status=error side={} reason={} stream=remote_camera_broker_stereo width={} height={} maxImages={} fpsCap={} nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false",
                    side.stable_id(),
                    sanitize_marker(&error),
                    width,
                    height,
                    max_images,
                    fps_cap
                ),
            );
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_StereoVideoPlayback_nativeStopRemoteCameraStream(
    _env: *mut JNIEnv,
    _class: jclass,
) {
    if let Ok(mut guard) = REMOTE_STREAM.lock() {
        let had_stream = guard.stop();
        marker(
            "remote-camera-broker-native-stream",
            format!(
                "status=stopped stream=remote_camera_broker_stereo hadStream={} nativeImageReader=true javaHardwareBufferBridge=false",
                had_stream
            ),
        );
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_StereoVideoPlayback_nativePumpRemoteCameraImage(
    _env: *mut JNIEnv,
    _class: jclass,
    side_code: jint,
) -> jint {
    let Some(side) = RemoteCameraSide::from_code(side_code) else {
        return -2;
    };
    if let Ok(guard) = REMOTE_STREAM.lock() {
        return unsafe { guard.pump_lane(side) };
    }
    -3
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_StereoVideoPlayback_nativeSetPackedStereoPairMetadata(
    _env: *mut JNIEnv,
    _class: jclass,
    pair_id: jlong,
    left_source_frame: jlong,
    right_source_frame: jlong,
    left_sensor_timestamp_ns: jlong,
    right_sensor_timestamp_ns: jlong,
    pair_delta_ns: jlong,
) {
    let measured_delta = left_sensor_timestamp_ns.abs_diff(right_sensor_timestamp_ns);
    let valid = pair_id > 0
        && left_source_frame > 0
        && right_source_frame > 0
        && left_sensor_timestamp_ns > 0
        && right_sensor_timestamp_ns > 0
        && pair_delta_ns >= 0
        && pair_delta_ns as u64 == measured_delta;
    if !valid {
        marker(
            "remote-camera-broker-packed-pair",
            format!(
                "status=rejected pairId={} leftSourceFrame={} rightSourceFrame={} leftSensorTimestampNs={} rightSensorTimestampNs={} pairDeltaNs={} reason=invalid-pair-metadata",
                pair_id,
                left_source_frame,
                right_source_frame,
                left_sensor_timestamp_ns,
                right_sensor_timestamp_ns,
                pair_delta_ns
            ),
        );
        return;
    }
    if let Ok(mut metadata) = REMOTE_PACKED_PAIR_METADATA.lock() {
        *metadata = Some(PackedPairMetadata {
            pair_id: pair_id as u64,
            left_source_frame: left_source_frame as u64,
            right_source_frame: right_source_frame as u64,
            left_sensor_timestamp_ns,
            right_sensor_timestamp_ns,
            pair_delta_ns: pair_delta_ns as u64,
        });
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_StereoVideoPlayback_nativeRemoteCameraLifecycleEvent(
    _env: *mut JNIEnv,
    _class: jclass,
    event_code: jint,
    side_code: jint,
    result_code: jint,
    width: jint,
    height: jint,
    max_images: jint,
    fps_cap: jint,
    port: jint,
) {
    let event_name = match event_code {
        EVENT_START_REQUESTED => "start-requested",
        2 => "started",
        EVENT_STOPPED => "stopped",
        EVENT_ERROR => "error",
        5 => "format",
        6 => "frame",
        EVENT_CONNECTED => "connected",
        EVENT_STREAM_HEADER => "stream-header",
        _ => "unknown",
    };
    let side_value = RemoteCameraSide::from_code(side_code);
    let side = side_value
        .map(RemoteCameraSide::stable_id)
        .unwrap_or("unknown");
    let latest_frame_invalidated = match (event_code, side_value) {
        (
            EVENT_START_REQUESTED
            | EVENT_STOPPED
            | EVENT_ERROR
            | EVENT_CONNECTED
            | EVENT_STREAM_HEADER,
            Some(side),
        ) => clear_latest_for_side(side),
        _ => false,
    };
    marker(
        "remote-camera-broker-inlet",
        format!(
            "status={} side={} stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264 resultCode={} width={} height={} maxImages={} fpsCap={} brokerPort={} latestFrameInvalidated={} staleLatestFrameGuard=true nativeImageReader=true javaHardwareBufferBridge=false rawCamera=false passthroughTexture=false environmentDepth=false geometryWitness=false highRateJsonPayload=false",
            event_name,
            side,
            result_code,
            width,
            height,
            max_images,
            fps_cap,
            port,
            latest_frame_invalidated
        ),
    );
}

impl RemoteCameraLaneStream {
    unsafe fn create_surface(
        env: *mut JNIEnv,
        side: RemoteCameraSide,
        width: i32,
        height: i32,
        max_images: i32,
        fps_cap: i32,
    ) -> Result<(Self, jobject), String> {
        let context = Box::into_raw(Box::new(RemoteCameraReaderContext {
            alive: AtomicBool::new(true),
            side,
            width,
            height,
            max_images,
            fps_cap,
            frame_count: AtomicU64::new(0),
            import_sequence: AtomicU64::new(0),
            acquire_errors: AtomicU64::new(0),
            dropped_frames: AtomicU64::new(0),
            buffer_removed_count: AtomicU64::new(0),
            last_accepted_timestamp_ns: AtomicI64::new(0),
        }));

        let mut reader = ptr::null_mut();
        let usage =
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
        let result = AImageReader_newWithUsage(
            width,
            height,
            AIMAGE_FORMAT_PRIVATE,
            usage,
            max_images,
            &mut reader,
        );
        if result != 0 || reader.is_null() {
            drop(Box::from_raw(context));
            return Err(format!(
                "AImageReader_newWithUsage failed result={result} format=private usage={usage}"
            ));
        }

        let mut listener = AImageReader_ImageListener {
            context: context.cast(),
            onImageAvailable: Some(remote_camera_on_image_available),
        };
        let listener_status = AImageReader_setImageListener(reader, &mut listener);
        if listener_status != 0 {
            AImageReader_delete(reader);
            drop(Box::from_raw(context));
            return Err(format!(
                "AImageReader_setImageListener failed result={listener_status}"
            ));
        }

        let mut buffer_listener = AImageReader_BufferRemovedListener {
            context: context.cast(),
            onBufferRemoved: Some(remote_camera_on_buffer_removed),
        };
        let buffer_listener_status =
            AImageReader_setBufferRemovedListener(reader, &mut buffer_listener);
        marker(
            "remote-camera-broker-buffer-removed-listener",
            format!(
                "status={} side={} stream=remote_camera_broker_stereo cacheEvictionSignal=true nativeImageReader=true",
                if buffer_listener_status == 0 { "registered" } else { "error" },
                side.stable_id()
            ),
        );

        let mut window = ptr::null_mut();
        let window_result = AImageReader_getWindow(reader, &mut window);
        if window_result != 0 || window.is_null() {
            AImageReader_delete(reader);
            drop(Box::from_raw(context));
            return Err(format!(
                "AImageReader_getWindow failed result={window_result}"
            ));
        }
        ANativeWindow_acquire(window);

        let surface = ANativeWindow_toSurface(env, window);
        if surface.is_null() {
            ANativeWindow_release(window);
            AImageReader_delete(reader);
            drop(Box::from_raw(context));
            return Err("ANativeWindow_toSurface returned null".to_string());
        }

        Ok((
            Self {
                reader,
                window,
                context,
            },
            surface,
        ))
    }
}

unsafe extern "C" fn remote_camera_on_image_available(
    context: *mut std::os::raw::c_void,
    reader: *mut AImageReader,
) {
    let _ = acquire_remote_camera_image(context, reader, "callback", true);
}

unsafe fn acquire_remote_camera_image(
    context: *mut std::os::raw::c_void,
    reader: *mut AImageReader,
    acquire_trigger: &'static str,
    log_empty_acquire: bool,
) -> bool {
    if context.is_null() {
        return false;
    }
    let reader_context = &*(context as *mut RemoteCameraReaderContext);
    if !reader_context.alive.load(Ordering::Acquire) {
        return false;
    }

    let mut image: *mut AImage = ptr::null_mut();
    let acquire_result = AImageReader_acquireLatestImage(reader, &mut image);
    if acquire_result != 0 || image.is_null() {
        if log_empty_acquire {
            let acquire_error_count = reader_context
                .acquire_errors
                .fetch_add(1, Ordering::Relaxed)
                + 1;
            marker(
                "remote-camera-broker-acquire",
                format!(
                    "status=error side={} stream=remote_camera_broker_stereo acquireResult={} imageNull={} width={} height={} maxImages={} acquireErrorCount={} imageAcquireApi=AImageReader_acquireLatestImage imageAcquireTrigger={} nativeImageReader=true javaHardwareBufferBridge=false",
                    reader_context.side.stable_id(),
                    acquire_result,
                    image.is_null(),
                    reader_context.width,
                    reader_context.height,
                    reader_context.max_images,
                    acquire_error_count,
                    acquire_trigger
                ),
            );
        }
        return false;
    }

    let mut timestamp_ns = 0_i64;
    let _ = AImage_getTimestamp(image, &mut timestamp_ns);
    if should_drop_for_fps_cap(reader_context, timestamp_ns) {
        let dropped = reader_context
            .dropped_frames
            .fetch_add(1, Ordering::Relaxed)
            + 1;
        if dropped == 1 || dropped % 60 == 0 {
            marker(
                "remote-camera-broker-acquire",
                format!(
                    "status=dropped-fps-cap side={} stream=remote_camera_broker_stereo droppedFrames={} fpsCap={} timestampNs={} nativeImageReader=true javaHardwareBufferBridge=false",
                    reader_context.side.stable_id(),
                    dropped,
                    reader_context.fps_cap,
                    timestamp_ns
                ),
            );
        }
        AImage_delete(image);
        return false;
    }

    let mut hardware_buffer_ptr = ptr::null_mut();
    if AImage_getHardwareBuffer(image, &mut hardware_buffer_ptr) != 0
        || hardware_buffer_ptr.is_null()
    {
        let acquire_error_count = reader_context
            .acquire_errors
            .fetch_add(1, Ordering::Relaxed)
            + 1;
        marker(
            "remote-camera-broker-ahardware-buffer",
            format!(
                "status=error reason=AImage_getHardwareBuffer side={} stream=remote_camera_broker_stereo acquireErrorCount={} timestampNs={} nativeImageReader=true javaHardwareBufferBridge=false",
                reader_context.side.stable_id(),
                acquire_error_count,
                timestamp_ns
            ),
        );
        AImage_delete(image);
        return false;
    }

    let hardware_buffer = match AndroidHardwareBufferHandle::acquire(hardware_buffer_ptr) {
        Ok(handle) => handle,
        Err(error) => {
            let acquire_error_count = reader_context
                .acquire_errors
                .fetch_add(1, Ordering::Relaxed)
                + 1;
            marker(
                "remote-camera-broker-ahardware-buffer",
                format!(
                    "status=error reason={} side={} stream=remote_camera_broker_stereo acquireErrorCount={} timestampNs={} nativeImageReader=true javaHardwareBufferBridge=false",
                    sanitize_marker(&error),
                    reader_context.side.stable_id(),
                    acquire_error_count,
                    timestamp_ns
                ),
            );
            AImage_delete(image);
            return false;
        }
    };

    let descriptor = hardware_buffer.descriptor();
    let frame_index = reader_context.frame_count.fetch_add(1, Ordering::Relaxed) + 1;
    let import_sequence = reader_context
        .import_sequence
        .fetch_add(1, Ordering::Relaxed)
        + 1;
    let dropped = reader_context.dropped_frames.load(Ordering::Relaxed);
    let buffer_removed_count = reader_context.buffer_removed_count.load(Ordering::Relaxed);
    let frame = NativeCameraFrame {
        side: reader_context.side.stable_id(),
        camera_id: reader_context.side.camera_id().to_string(),
        width: descriptor.width.max(1),
        height: descriptor.height.max(1),
        timestamp_ns,
        source_frame: frame_index,
        import_sequence,
        hardware_buffer_id: descriptor.hardware_buffer_id,
        native_format: descriptor.format,
        usage: descriptor.usage,
        layers: descriptor.layers,
        stride: descriptor.stride,
        image_dataspace: None,
        image_dataspace_status: "remote-mediacodec-surface".to_string(),
        hardware_buffer,
        image_lease: None,
        capture_result: NativeCameraCaptureResultCorrelation::unavailable(),
    };
    let mut marker_pair_id = 0_u64;
    let mut marker_left_source_frame = 0_u64;
    let mut marker_right_source_frame = 0_u64;
    let mut marker_pair_delta_ns = 0_u64;
    match reader_context.side {
        RemoteCameraSide::Left => {
            if let Ok(mut latest) = REMOTE_LATEST_LEFT.lock() {
                *latest = Some(frame);
            }
        }
        RemoteCameraSide::Right => {
            if let Ok(mut latest) = REMOTE_LATEST_RIGHT.lock() {
                *latest = Some(frame);
            }
        }
        RemoteCameraSide::Packed => {
            let pair = REMOTE_PACKED_PAIR_METADATA
                .lock()
                .ok()
                .and_then(|metadata| *metadata);
            let Some(pair) = pair else {
                marker(
                    "remote-camera-broker-packed-pair",
                    format!(
                        "status=rejected reason=missing-pair-metadata decodedTimestampNs={} sourceFrame={} latestFramePublished=false",
                        timestamp_ns, frame_index
                    ),
                );
                AImage_delete(image);
                return false;
            };
            let mut left = frame.clone();
            left.side = "left";
            left.camera_id = "remote-broker-packed-left-50".to_string();
            left.timestamp_ns = pair.left_sensor_timestamp_ns;
            left.source_frame = pair.left_source_frame;
            let mut right = frame;
            right.side = "right";
            right.camera_id = "remote-broker-packed-right-51".to_string();
            right.timestamp_ns = pair.right_sensor_timestamp_ns;
            right.source_frame = pair.right_source_frame;
            if let Ok(mut latest) = REMOTE_LATEST_LEFT.lock() {
                *latest = Some(left);
            }
            if let Ok(mut latest) = REMOTE_LATEST_RIGHT.lock() {
                *latest = Some(right);
            }
            marker_pair_id = pair.pair_id;
            marker_left_source_frame = pair.left_source_frame;
            marker_right_source_frame = pair.right_source_frame;
            marker_pair_delta_ns = pair.pair_delta_ns;
        }
    }
    marker(
        "remote-camera-broker-ahardware-buffer",
        format!(
            "status=frame side={} stream=remote_camera_broker_stereo sourceFrame={} importSequence={} timestampNs={} stereoPairId={} leftSourceFrame={} rightSourceFrame={} pairDeltaNs={} packedStereo={} descriptorWidth={} descriptorHeight={} descriptorLayers={} descriptorFormat={} descriptorUsage={} descriptorStride={} hardwareBufferId={} hardwareBufferIdStatus={} configuredWidth={} configuredHeight={} maxImages={} fpsCap={} droppedFrames={} bufferRemovedCount={} imageAcquireApi=AImageReader_acquireLatestImage imageReleaseApi=AImage_delete descriptorShape=android-hardware-buffer-private sourceAuthority=manifold-broker-rmanvid1-camera2-h264 rawCamera=false passthroughTexture=false environmentDepth=false geometryWitness=false highRateJsonPayload=false nativeImageReader=true nativeImageReaderCount=1 javaHardwareBufferBridge=false cpuPixelCopy=false ahbHandleRetained=true latestFramePublished=true cameraProjectionGpuImportReady=false remoteBrokerCameraProjectionActive=true remoteCameraGpuAdoptionPath=rmanvid1-mediacodec-surface-aimage-reader-ahardwarebuffer-to-vulkan-camera-projection",
            reader_context.side.stable_id(),
            frame_index,
            import_sequence,
            timestamp_ns,
            marker_pair_id,
            marker_left_source_frame,
            marker_right_source_frame,
            marker_pair_delta_ns,
            matches!(reader_context.side, RemoteCameraSide::Packed),
            descriptor.width,
            descriptor.height,
            descriptor.layers,
            descriptor.format,
            descriptor.usage,
            descriptor.stride,
            descriptor.hardware_buffer_id,
            descriptor.hardware_buffer_id_status,
            reader_context.width,
            reader_context.height,
            reader_context.max_images,
            reader_context.fps_cap,
            dropped,
            buffer_removed_count
        ),
    );
    AImage_delete(image);
    true
}

unsafe extern "C" fn remote_camera_on_buffer_removed(
    context: *mut std::os::raw::c_void,
    _reader: *mut AImageReader,
    buffer: *mut ndk_sys::AHardwareBuffer,
) {
    if context.is_null() {
        return;
    }
    let reader_context = &*(context as *mut RemoteCameraReaderContext);
    let count = reader_context
        .buffer_removed_count
        .fetch_add(1, Ordering::Relaxed)
        + 1;
    let descriptor = if buffer.is_null() {
        None
    } else {
        AndroidHardwareBufferHandle::acquire(buffer)
            .ok()
            .map(|handle| handle.descriptor())
    };
    marker(
        "remote-camera-broker-buffer-removed",
        format!(
            "status=buffer-removed side={} stream=remote_camera_broker_stereo removedCount={} hardwareBufferId={} descriptorWidth={} descriptorHeight={} nativeImageReader=true javaHardwareBufferBridge=false cacheEvictionSignal=true",
            reader_context.side.stable_id(),
            count,
            descriptor.map(|desc| desc.hardware_buffer_id).unwrap_or(0),
            descriptor.map(|desc| desc.width).unwrap_or(0),
            descriptor.map(|desc| desc.height).unwrap_or(0)
        ),
    );
}

fn should_drop_for_fps_cap(reader_context: &RemoteCameraReaderContext, timestamp_ns: i64) -> bool {
    if timestamp_ns > 0 {
        reader_context
            .last_accepted_timestamp_ns
            .store(timestamp_ns, Ordering::Relaxed);
    }
    // Remote broker frames already arrive at the sender's media profile rate.
    // Dropping decoded AImageReader frames here can starve MediaCodec output
    // and hide the latest camera frame from the native projection path.
    false
}

fn sanitize_marker(value: &str) -> String {
    value
        .replace('\0', "\\0")
        .replace('\r', " ")
        .replace('\n', " ")
        .replace('"', "'")
}
