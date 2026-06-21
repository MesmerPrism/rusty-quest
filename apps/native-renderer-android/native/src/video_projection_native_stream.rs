//! Native Android video projection source.
//!
//! Java owns MediaCodec control and writes decoded frames into a Rust-created
//! `AImageReader` surface. Rust receives native `AImage` / `AHardwareBuffer`
//! objects so the renderer can import decoded video without Java per-frame
//! hardware-buffer objects or CPU pixel copies.

use std::{
    ptr,
    sync::{
        atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering},
        Mutex,
    },
};

use jni::sys::{jclass, jint, jobject, JNIEnv};

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
    android_hardware_buffer::{AndroidHardwareBufferDescriptor, AndroidHardwareBufferHandle},
    marker,
};

static VIDEO_PROJECTION_STREAM: Mutex<Option<NativeVideoProjectionStream>> = Mutex::new(None);
static VIDEO_PROJECTION_LATEST_FRAME: Mutex<Option<VideoProjectionFrame>> = Mutex::new(None);

#[derive(Clone, Debug)]
pub(crate) struct VideoProjectionFrame {
    pub(crate) hardware_buffer: AndroidHardwareBufferHandle,
    pub(crate) descriptor: AndroidHardwareBufferDescriptor,
    pub(crate) frame_index: u64,
    pub(crate) import_sequence: u64,
    pub(crate) timestamp_ns: i64,
    pub(crate) configured_width: i32,
    pub(crate) configured_height: i32,
    pub(crate) max_images: i32,
    pub(crate) fps_cap: i32,
    pub(crate) dropped_frames: u64,
    pub(crate) buffer_removed_count: u64,
}

pub(crate) fn latest_video_projection_frame() -> Option<VideoProjectionFrame> {
    VIDEO_PROJECTION_LATEST_FRAME
        .lock()
        .ok()
        .and_then(|guard| guard.as_ref().cloned())
}

struct NativeVideoProjectionStream {
    reader: *mut AImageReader,
    window: *mut ANativeWindow,
    context: *mut NativeVideoProjectionReaderContext,
}

unsafe impl Send for NativeVideoProjectionStream {}

impl Drop for NativeVideoProjectionStream {
    fn drop(&mut self) {
        unsafe {
            if !self.context.is_null() {
                (*self.context).alive.store(false, Ordering::Release);
            }
            if let Ok(mut latest) = VIDEO_PROJECTION_LATEST_FRAME.lock() {
                *latest = None;
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

struct NativeVideoProjectionReaderContext {
    alive: AtomicBool,
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

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_StereoVideoPlayback_nativeCreateStereoVideoSurface(
    env: *mut JNIEnv,
    _class: jclass,
    width: jint,
    height: jint,
    max_images: jint,
    fps_cap: jint,
) -> jobject {
    if env.is_null() {
        marker(
            "video-projection-native-stream",
            "status=error reason=null-jni-env nativeImageReader=true javaHardwareBufferBridge=false",
        );
        return ptr::null_mut();
    }

    let width = width.clamp(320, 4096);
    let height = height.clamp(240, 4096);
    let max_images = max_images.clamp(2, 6);
    let fps_cap = fps_cap.clamp(1, 90);

    let mut guard = match VIDEO_PROJECTION_STREAM.lock() {
        Ok(guard) => guard,
        Err(_) => {
            marker(
                "video-projection-native-stream",
                "status=error reason=stream-lock-poisoned nativeImageReader=true javaHardwareBufferBridge=false",
            );
            return ptr::null_mut();
        }
    };
    *guard = None;

    match unsafe {
        NativeVideoProjectionStream::create_surface(env, width, height, max_images, fps_cap)
    } {
        Ok((stream, surface)) => {
            marker(
                "video-projection-native-stream",
                format!(
                    "status=surface-created stream=stereo_video width={} height={} maxImages={} fpsCap={} format=private usage=gpu-sampled-image|gpu-color-output nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false",
                    width, height, max_images, fps_cap
                ),
            );
            *guard = Some(stream);
            surface
        }
        Err(error) => {
            marker(
                "video-projection-native-stream",
                format!(
                    "status=error reason={} stream=stereo_video width={} height={} maxImages={} fpsCap={} nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false",
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
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_StereoVideoPlayback_nativeStopStereoVideoStream(
    _env: *mut JNIEnv,
    _class: jclass,
) {
    if let Ok(mut guard) = VIDEO_PROJECTION_STREAM.lock() {
        let had_stream = guard.take().is_some();
        if let Ok(mut latest) = VIDEO_PROJECTION_LATEST_FRAME.lock() {
            *latest = None;
        }
        marker(
            "video-projection-native-stream",
            format!(
                "status=stopped stream=stereo_video hadStream={} nativeImageReader=true javaHardwareBufferBridge=false",
                had_stream
            ),
        );
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_StereoVideoPlayback_nativeStereoVideoLifecycleEvent(
    _env: *mut JNIEnv,
    _class: jclass,
    event_code: jint,
    result_code: jint,
    width: jint,
    height: jint,
    max_images: jint,
    fps_cap: jint,
    looping: jint,
) {
    let event_name = match event_code {
        1 => "start-requested",
        2 => "started",
        3 => "stopped",
        4 => "error",
        5 => "format",
        6 => "frame",
        7 => "loop-restarted",
        _ => "unknown",
    };
    marker(
        "video-projection-playback",
        format!(
            "status={} stream=stereo_video sourceAuthority=android-mediacodec-surface-decoder resultCode={} width={} height={} maxImages={} fpsCap={} looping={} nativeImageReader=true javaHardwareBufferBridge=false rawCamera=false passthroughTexture=false environmentDepth=false geometryWitness=false highRateJsonPayload=false",
            event_name,
            result_code,
            width,
            height,
            max_images,
            fps_cap,
            looping != 0
        ),
    );
}

impl NativeVideoProjectionStream {
    unsafe fn create_surface(
        env: *mut JNIEnv,
        width: i32,
        height: i32,
        max_images: i32,
        fps_cap: i32,
    ) -> Result<(Self, jobject), String> {
        let context = Box::into_raw(Box::new(NativeVideoProjectionReaderContext {
            alive: AtomicBool::new(true),
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
            onImageAvailable: Some(video_projection_on_image_available),
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
            onBufferRemoved: Some(video_projection_on_buffer_removed),
        };
        let buffer_listener_status =
            AImageReader_setBufferRemovedListener(reader, &mut buffer_listener);
        marker(
            "video-projection-buffer-removed-listener",
            format!(
                "status={} stream=stereo_video cacheEvictionSignal=true nativeImageReader=true",
                if buffer_listener_status == 0 {
                    "registered"
                } else {
                    "error"
                }
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

unsafe extern "C" fn video_projection_on_image_available(
    context: *mut std::os::raw::c_void,
    reader: *mut AImageReader,
) {
    if context.is_null() {
        return;
    }
    let reader_context = &*(context as *mut NativeVideoProjectionReaderContext);
    if !reader_context.alive.load(Ordering::Acquire) {
        return;
    }

    let mut image: *mut AImage = ptr::null_mut();
    let acquire_result = AImageReader_acquireLatestImage(reader, &mut image);
    if acquire_result != 0 || image.is_null() {
        let acquire_error_count = reader_context
            .acquire_errors
            .fetch_add(1, Ordering::Relaxed)
            + 1;
        marker(
            "video-projection-acquire",
            format!(
                "status=error stream=stereo_video acquireResult={} imageNull={} width={} height={} maxImages={} acquireErrorCount={} imageAcquireApi=AImageReader_acquireLatestImage nativeImageReader=true javaHardwareBufferBridge=false",
                acquire_result,
                image.is_null(),
                reader_context.width,
                reader_context.height,
                reader_context.max_images,
                acquire_error_count
            ),
        );
        return;
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
                "video-projection-acquire",
                format!(
                    "status=dropped-fps-cap stream=stereo_video droppedFrames={} fpsCap={} timestampNs={} nativeImageReader=true javaHardwareBufferBridge=false",
                    dropped,
                    reader_context.fps_cap,
                    timestamp_ns
                ),
            );
        }
        AImage_delete(image);
        return;
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
            "video-projection-ahardware-buffer",
            format!(
                "status=error reason=AImage_getHardwareBuffer stream=stereo_video acquireErrorCount={} timestampNs={} nativeImageReader=true javaHardwareBufferBridge=false",
                acquire_error_count,
                timestamp_ns
            ),
        );
        AImage_delete(image);
        return;
    }

    let hardware_buffer = match AndroidHardwareBufferHandle::acquire(hardware_buffer_ptr) {
        Ok(handle) => handle,
        Err(error) => {
            let acquire_error_count = reader_context
                .acquire_errors
                .fetch_add(1, Ordering::Relaxed)
                + 1;
            marker(
                "video-projection-ahardware-buffer",
                format!(
                    "status=error reason={} stream=stereo_video acquireErrorCount={} timestampNs={} nativeImageReader=true javaHardwareBufferBridge=false",
                    sanitize_marker(&error),
                    acquire_error_count,
                    timestamp_ns
                ),
            );
            AImage_delete(image);
            return;
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
    let frame = VideoProjectionFrame {
        hardware_buffer,
        descriptor,
        frame_index,
        import_sequence,
        timestamp_ns,
        configured_width: reader_context.width,
        configured_height: reader_context.height,
        max_images: reader_context.max_images,
        fps_cap: reader_context.fps_cap,
        dropped_frames: dropped,
        buffer_removed_count,
    };
    if let Ok(mut latest) = VIDEO_PROJECTION_LATEST_FRAME.lock() {
        *latest = Some(frame.clone());
    }
    marker(
        "video-projection-ahardware-buffer",
        format!(
            "status=frame stream=stereo_video frameIndex={} importSequence={} timestampNs={} descriptorWidth={} descriptorHeight={} descriptorLayers={} descriptorFormat={} descriptorUsage={} descriptorStride={} hardwareBufferId={} hardwareBufferIdStatus={} configuredWidth={} configuredHeight={} maxImages={} fpsCap={} droppedFrames={} bufferRemovedCount={} imageAcquireApi=AImageReader_acquireLatestImage imageReleaseApi=AImage_delete descriptorShape=android-hardware-buffer-private sourceAuthority=android-mediacodec-surface-decoder rawCamera=false passthroughTexture=false environmentDepth=false geometryWitness=false highRateJsonPayload=false nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false ahbHandleRetained=true latestFramePublished=true videoProjectionGpuImportReady=false videoProjectionGpuAdoptionPath=android-mediacodec-surface-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image",
            frame_index,
            import_sequence,
            timestamp_ns,
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
}

unsafe extern "C" fn video_projection_on_buffer_removed(
    context: *mut std::os::raw::c_void,
    _reader: *mut AImageReader,
    buffer: *mut ndk_sys::AHardwareBuffer,
) {
    if context.is_null() {
        return;
    }
    let reader_context = &*(context as *mut NativeVideoProjectionReaderContext);
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
        "video-projection-buffer-removed",
        format!(
            "status=buffer-removed stream=stereo_video removedCount={} hardwareBufferId={} descriptorWidth={} descriptorHeight={} nativeImageReader=true javaHardwareBufferBridge=false cacheEvictionSignal=true",
            count,
            descriptor.map(|desc| desc.hardware_buffer_id).unwrap_or(0),
            descriptor.map(|desc| desc.width).unwrap_or(0),
            descriptor.map(|desc| desc.height).unwrap_or(0)
        ),
    );
}

fn should_drop_for_fps_cap(
    reader_context: &NativeVideoProjectionReaderContext,
    timestamp_ns: i64,
) -> bool {
    if timestamp_ns <= 0 {
        return false;
    }
    let minimum_gap_ns = 1_000_000_000_i64 / i64::from(reader_context.fps_cap.max(1));
    let previous = reader_context
        .last_accepted_timestamp_ns
        .load(Ordering::Relaxed);
    if previous > 0 && timestamp_ns.saturating_sub(previous) < minimum_gap_ns {
        return true;
    }
    reader_context
        .last_accepted_timestamp_ns
        .store(timestamp_ns, Ordering::Relaxed);
    false
}

fn sanitize_marker(value: &str) -> String {
    value
        .replace('\0', "\\0")
        .replace('\r', " ")
        .replace('\n', " ")
        .replace('"', "'")
}
