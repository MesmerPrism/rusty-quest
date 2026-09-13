//! Native Android video stream for the Spatial camera-panel renderer.
//!
//! Java owns MediaCodec control and writes decoded frames into a Rust-created
//! `AImageReader` surface. Rust receives native `AImage` / `AHardwareBuffer`
//! objects for Vulkan import without Java hardware-buffer bridges or CPU copies.

use std::{
    collections::BTreeMap,
    os::raw::c_void,
    ptr,
    sync::{
        atomic::{AtomicI64, AtomicU64, Ordering},
        Arc, LazyLock, Mutex,
    },
};

use jni::sys::{jboolean, jclass, jint, jlong, jobject, JNIEnv};

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
    marker_token, peer_projection_runtime,
    projection_frame_source::{
        projection_peer_stop_result, CallbackGate, CallbackPermit, PackedFrameIdentity,
        ProjectionDecoderRole, ProjectionFrameSourceState,
    },
    spatial_video_projection_marker::log_spatial_video_projection_marker as log_marker,
    spatial_video_projection_qualification,
    spatial_video_projection_settings::{
        should_drop_spatial_video_projection_timestamp, should_log_spatial_video_projection_frame,
    },
};

static SPATIAL_VIDEO_PROJECTION_STREAM: Mutex<Option<NativeSpatialVideoProjectionStream>> =
    Mutex::new(None);
static SPATIAL_VIDEO_PROJECTION_LATEST_FRAME: Mutex<Option<SpatialVideoProjectionFrame>> =
    Mutex::new(None);
static PROJECTION_PEER_STREAM: Mutex<Option<NativeSpatialVideoProjectionStream>> = Mutex::new(None);
static PROJECTION_PEER_LATEST_FRAME: Mutex<Option<SpatialVideoProjectionFrame>> = Mutex::new(None);
static PROJECTION_PEER_BINDING: Mutex<Option<(u64, u64, u64)>> = Mutex::new(None);
static PROJECTION_PEER_RETIRED_IDENTITY: Mutex<Option<(u64, u64)>> = Mutex::new(None);
static SPATIAL_VIDEO_PROJECTION_CONTEXTS: LazyLock<
    Mutex<BTreeMap<u64, Arc<NativeSpatialVideoProjectionReaderContext>>>,
> = LazyLock::new(|| Mutex::new(BTreeMap::new()));
static NEXT_SPATIAL_VIDEO_READER_GENERATION: AtomicU64 = AtomicU64::new(1);

pub(crate) type SpatialPackedPairMetadata = PackedFrameIdentity;

#[derive(Debug)]
struct SpatialVideoProjectionReaderLifetime {
    reader: *mut AImageReader,
}

unsafe impl Send for SpatialVideoProjectionReaderLifetime {}
unsafe impl Sync for SpatialVideoProjectionReaderLifetime {}

impl Drop for SpatialVideoProjectionReaderLifetime {
    fn drop(&mut self) {
        if !self.reader.is_null() {
            unsafe {
                AImageReader_delete(self.reader);
            }
            self.reader = ptr::null_mut();
        }
    }
}

#[derive(Debug)]
pub(crate) struct SpatialVideoProjectionImageLease {
    image: *mut AImage,
    _reader_lifetime: Arc<SpatialVideoProjectionReaderLifetime>,
}

unsafe impl Send for SpatialVideoProjectionImageLease {}
unsafe impl Sync for SpatialVideoProjectionImageLease {}

impl Drop for SpatialVideoProjectionImageLease {
    fn drop(&mut self) {
        if !self.image.is_null() {
            unsafe {
                AImage_delete(self.image);
            }
            self.image = ptr::null_mut();
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct SpatialVideoProjectionFrame {
    pub(crate) hardware_buffer: AndroidHardwareBufferHandle,
    pub(crate) descriptor: AndroidHardwareBufferDescriptor,
    pub(crate) frame_index: u64,
    pub(crate) import_sequence: u64,
    pub(crate) timestamp_ns: i64,
    pub(crate) decoder_token: u64,
    pub(crate) reader_generation: u64,
    pub(crate) role: ProjectionDecoderRole,
    pub(crate) route_generation: u64,
    pub(crate) configured_width: i32,
    pub(crate) configured_height: i32,
    pub(crate) max_images: i32,
    pub(crate) fps_cap: i32,
    pub(crate) dropped_frames: u64,
    pub(crate) buffer_removed_count: u64,
    pub(crate) removed_hardware_buffer_ids: Vec<u64>,
    pub(crate) buffer_reuse_disabled: bool,
    pub(crate) packed_pair: Option<SpatialPackedPairMetadata>,
    pub(crate) image_lease: Arc<SpatialVideoProjectionImageLease>,
}

pub(crate) fn latest_spatial_video_projection_frame() -> Option<SpatialVideoProjectionFrame> {
    latest_frame_for(&SPATIAL_VIDEO_PROJECTION_LATEST_FRAME)
}

pub(crate) fn latest_projection_peer_frame() -> Option<SpatialVideoProjectionFrame> {
    let frame = latest_frame_for(&PROJECTION_PEER_LATEST_FRAME)?;
    projection_peer_binding_matches(
        frame.route_generation,
        frame.decoder_token,
        frame.reader_generation,
    )
    .then_some(frame)
}

fn projection_peer_binding_matches(
    route_generation: u64,
    decoder_token: u64,
    reader_generation: u64,
) -> bool {
    PROJECTION_PEER_BINDING.lock().ok().is_some_and(|binding| {
        binding.as_ref().is_some_and(|(route, decoder, reader)| {
            *route == route_generation && *decoder == decoder_token && *reader == reader_generation
        })
    })
}

fn latest_frame_for(
    slot: &Mutex<Option<SpatialVideoProjectionFrame>>,
) -> Option<SpatialVideoProjectionFrame> {
    let mut frame = slot.lock().ok().and_then(|guard| guard.as_ref().cloned())?;
    let context = SPATIAL_VIDEO_PROJECTION_CONTEXTS
        .lock()
        .ok()
        .and_then(|contexts| contexts.get(&frame.reader_generation).cloned());
    match context.and_then(|context| {
        context
            .source
            .lock()
            .ok()
            .map(|source| source.buffer_removal_snapshot())
    }) {
        Some((removed_ids, reuse_disabled)) => {
            frame.removed_hardware_buffer_ids = removed_ids;
            frame.buffer_reuse_disabled = reuse_disabled;
        }
        None => {
            // Losing the exact generation state can never authorize cache reuse.
            frame.removed_hardware_buffer_ids.clear();
            frame.buffer_reuse_disabled = true;
        }
    }
    Some(frame)
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialPackedStereoBrokerPlayback_nativeRegisterPackedStereoPairMetadata(
    _env: *mut JNIEnv,
    _class: jclass,
    decoder_token: jlong,
    output_timestamp_ns: jlong,
    pair_id: jlong,
    left_source_frame: jlong,
    right_source_frame: jlong,
    left_sensor_timestamp_ns: jlong,
    right_sensor_timestamp_ns: jlong,
    pair_delta_ns: jlong,
) -> jboolean {
    if decoder_token <= 0
        || output_timestamp_ns < 0
        || pair_id <= 0
        || left_source_frame <= 0
        || right_source_frame <= 0
        || left_sensor_timestamp_ns <= 0
        || right_sensor_timestamp_ns <= 0
        || pair_delta_ns < 0
    {
        log_marker("status=packed-pair-rejected reason=invalid-pair-metadata".to_string());
        return 0;
    }
    let Some((context, _permit)) = context_for_decoder_token(decoder_token as u64) else {
        log_marker("status=packed-pair-rejected reason=stale-decoder-token".to_string());
        return 0;
    };
    let Ok(mut source) = context.source.lock() else {
        log_marker("status=packed-pair-rejected reason=identity-lock-poisoned".to_string());
        return 0;
    };
    let accepted = source
        .register_packed(
            output_timestamp_ns,
            SpatialPackedPairMetadata {
                pair_id: pair_id as u64,
                left_source_frame: left_source_frame as u64,
                right_source_frame: right_source_frame as u64,
                left_sensor_timestamp_ns,
                right_sensor_timestamp_ns,
                pair_delta_ns: pair_delta_ns as u64,
            },
        )
        .is_ok();
    if !accepted {
        log_marker(
            "status=packed-pair-rejected reason=duplicate-missing-or-bounded-identity".to_string(),
        );
    }
    if accepted {
        1
    } else {
        0
    }
}

struct NativeSpatialVideoProjectionStream {
    window: *mut ANativeWindow,
    reader_lifetime: Arc<SpatialVideoProjectionReaderLifetime>,
    context: Arc<NativeSpatialVideoProjectionReaderContext>,
}

unsafe impl Send for NativeSpatialVideoProjectionStream {}

impl Drop for NativeSpatialVideoProjectionStream {
    fn drop(&mut self) {
        self.context.callback_gate.deactivate();
        if let Ok(mut contexts) = SPATIAL_VIDEO_PROJECTION_CONTEXTS.lock() {
            if contexts
                .get(&self.context.reader_generation)
                .is_some_and(|registered| Arc::ptr_eq(registered, &self.context))
            {
                contexts.remove(&self.context.reader_generation);
            }
        }
        unsafe {
            let reader = self.reader_lifetime.reader;
            let image_listener_status = AImageReader_setImageListener(reader, ptr::null_mut());
            let buffer_listener_status =
                AImageReader_setBufferRemovedListener(reader, ptr::null_mut());
            log_marker(format!(
                "status=listeners-unregistered stream=stereo_video readerGeneration={} imageListenerResult={} bufferListenerResult={}",
                self.context.reader_generation, image_listener_status, buffer_listener_status
            ));
        }
        self.context.callback_gate.wait_for_quiescence();
        let latest_slot = match self.context.role {
            ProjectionDecoderRole::CompositorVideo => &SPATIAL_VIDEO_PROJECTION_LATEST_FRAME,
            ProjectionDecoderRole::ProjectionPeer => &PROJECTION_PEER_LATEST_FRAME,
        };
        if let Ok(mut latest) = latest_slot.lock() {
            if latest
                .as_ref()
                .is_some_and(|frame| frame.reader_generation == self.context.reader_generation)
            {
                *latest = None;
            }
        }
        unsafe {
            if !self.window.is_null() {
                ANativeWindow_release(self.window);
                self.window = ptr::null_mut();
            }
        }
    }
}

struct NativeSpatialVideoProjectionReaderContext {
    reader_generation: u64,
    decoder_token: u64,
    role: ProjectionDecoderRole,
    route_generation: u64,
    reader_address: usize,
    reader_lifetime: Arc<SpatialVideoProjectionReaderLifetime>,
    callback_gate: Arc<CallbackGate>,
    source: Mutex<ProjectionFrameSourceState>,
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

unsafe impl Send for NativeSpatialVideoProjectionReaderContext {}
unsafe impl Sync for NativeSpatialVideoProjectionReaderContext {}

fn next_reader_generation() -> Result<u64, String> {
    let generation = NEXT_SPATIAL_VIDEO_READER_GENERATION
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |current| {
            (current != 0 && current != u64::MAX).then_some(current + 1)
        })
        .map_err(|_| "AImageReader generation space exhausted".to_string())?;
    usize::try_from(generation)
        .map_err(|_| "AImageReader generation does not fit callback context".to_string())?;
    Ok(generation)
}

fn context_token(reader_generation: u64) -> *mut c_void {
    reader_generation as usize as *mut c_void
}

fn context_for_decoder_token(
    decoder_token: u64,
) -> Option<(
    Arc<NativeSpatialVideoProjectionReaderContext>,
    CallbackPermit,
)> {
    let context = SPATIAL_VIDEO_PROJECTION_CONTEXTS
        .lock()
        .ok()?
        .values()
        .find(|context| context.decoder_token == decoder_token)
        .cloned()?;
    let permit = context.callback_gate.try_enter()?;
    Some((context, permit))
}

fn context_for_callback(
    token: *mut c_void,
    reader: *mut AImageReader,
) -> Option<(
    Arc<NativeSpatialVideoProjectionReaderContext>,
    CallbackPermit,
)> {
    let reader_generation = token as usize as u64;
    if reader_generation == 0 || reader.is_null() {
        return None;
    }
    let context = SPATIAL_VIDEO_PROJECTION_CONTEXTS
        .lock()
        .ok()?
        .get(&reader_generation)
        .cloned()?;
    if context.reader_address != reader as usize {
        return None;
    }
    let permit = context.callback_gate.try_enter()?;
    Some((context, permit))
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialStereoVideoPlayback_nativeCreateStereoVideoSurface(
    env: *mut JNIEnv,
    _class: jclass,
    width: jint,
    height: jint,
    max_images: jint,
    fps_cap: jint,
    decoder_token: jlong,
    packed_identity_required: jboolean,
) -> jobject {
    if env.is_null() || decoder_token <= 0 {
        log_marker(
            "status=error reason=null-jni-env-or-decoder-token nativeImageReader=true javaHardwareBufferBridge=false"
                .to_string(),
        );
        return ptr::null_mut();
    }

    let width = width.clamp(320, 4096);
    let height = height.clamp(240, 4096);
    let max_images = max_images.clamp(4, 6);
    let fps_cap = fps_cap.clamp(1, 90);

    let mut guard = match SPATIAL_VIDEO_PROJECTION_STREAM.lock() {
        Ok(guard) => guard,
        Err(_) => {
            log_marker(
                "status=error reason=stream-lock-poisoned nativeImageReader=true javaHardwareBufferBridge=false"
                    .to_string(),
            );
            return ptr::null_mut();
        }
    };
    *guard = None;

    match unsafe {
        NativeSpatialVideoProjectionStream::create_surface(
            env,
            width,
            height,
            max_images,
            fps_cap,
            decoder_token as u64,
            ProjectionDecoderRole::CompositorVideo,
            0,
            packed_identity_required != 0,
        )
    } {
        Ok((stream, surface)) => {
            log_marker(format!(
                "status=surface-created stream=stereo_video width={} height={} maxImages={} fpsCap={} format=private usage=gpu-sampled-image|gpu-color-output nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false",
                width, height, max_images, fps_cap
            ));
            *guard = Some(stream);
            surface
        }
        Err(error) => {
            log_marker(format!(
                "status=error reason={} stream=stereo_video width={} height={} maxImages={} fpsCap={} nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false",
                marker_token(&error),
                width,
                height,
                max_images,
                fps_cap
            ));
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialStereoVideoPlayback_nativeStopStereoVideoStream(
    _env: *mut JNIEnv,
    _class: jclass,
) {
    if let Ok(mut guard) = SPATIAL_VIDEO_PROJECTION_STREAM.lock() {
        let had_stream = guard.take().is_some();
        if let Ok(mut latest) = SPATIAL_VIDEO_PROJECTION_LATEST_FRAME.lock() {
            *latest = None;
        }
        log_marker(format!(
            "status=stopped stream=stereo_video hadStream={} nativeImageReader=true javaHardwareBufferBridge=false",
            had_stream
        ));
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialStereoVideoPlayback_nativeCreateProjectionPeerVideoSurface(
    env: *mut JNIEnv,
    _class: jclass,
    width: jint,
    height: jint,
    max_images: jint,
    fps_cap: jint,
    decoder_token: jlong,
    route_generation: jlong,
) -> jobject {
    if env.is_null() || decoder_token <= 0 || route_generation <= 0 {
        return ptr::null_mut();
    }
    let mut guard = match PROJECTION_PEER_STREAM.lock() {
        Ok(guard) => guard,
        Err(_) => return ptr::null_mut(),
    };
    if guard.is_some() {
        return ptr::null_mut();
    }
    match unsafe {
        NativeSpatialVideoProjectionStream::create_surface(
            env,
            width.clamp(320, 4096),
            height.clamp(240, 4096),
            max_images.clamp(4, 6),
            fps_cap.clamp(1, 90),
            decoder_token as u64,
            ProjectionDecoderRole::ProjectionPeer,
            route_generation as u64,
            true,
        )
    } {
        Ok((stream, surface)) => {
            *guard = Some(stream);
            surface
        }
        Err(error) => {
            log_marker(format!(
                "status=projection-peer-surface-error reason={}",
                marker_token(&error)
            ));
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialStereoVideoPlayback_nativeProjectionPeerReaderGeneration(
    _env: *mut JNIEnv,
    _class: jclass,
    route_generation: jlong,
    decoder_token: jlong,
) -> jlong {
    if route_generation <= 0 || decoder_token <= 0 {
        return 0;
    }
    PROJECTION_PEER_STREAM
        .lock()
        .ok()
        .and_then(|guard| {
            guard.as_ref().and_then(|stream| {
                (stream.context.role == ProjectionDecoderRole::ProjectionPeer
                    && stream.context.route_generation == route_generation as u64
                    && stream.context.decoder_token == decoder_token as u64)
                    .then_some(stream.context.reader_generation as jlong)
            })
        })
        .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialStereoVideoPlayback_nativeStopProjectionPeerVideoStream(
    _env: *mut JNIEnv,
    _class: jclass,
    route_generation: jlong,
    decoder_token: jlong,
) -> jint {
    if route_generation <= 0 || decoder_token <= 0 {
        return 0;
    }
    let Ok(mut guard) = PROJECTION_PEER_STREAM.lock() else {
        return 0;
    };
    let matches = guard.as_ref().is_some_and(|stream| {
        stream.context.role == ProjectionDecoderRole::ProjectionPeer
            && stream.context.route_generation == route_generation as u64
            && stream.context.decoder_token == decoder_token as u64
    });
    if !matches {
        let retired_match = PROJECTION_PEER_RETIRED_IDENTITY
            .lock()
            .ok()
            .is_some_and(|retired| {
                retired.as_ref().is_some_and(|(route, decoder)| {
                    *route == route_generation as u64 && *decoder == decoder_token as u64
                })
            });
        return projection_peer_stop_result(false, retired_match);
    }
    guard.take();
    if let Ok(mut binding) = PROJECTION_PEER_BINDING.lock() {
        if binding.as_ref().is_some_and(|(route, decoder, _)| {
            *route == route_generation as u64 && *decoder == decoder_token as u64
        }) {
            *binding = None;
        }
    }
    if let Ok(mut latest) = PROJECTION_PEER_LATEST_FRAME.lock() {
        if latest.as_ref().is_some_and(|frame| {
            frame.route_generation == route_generation as u64
                && frame.decoder_token == decoder_token as u64
        }) {
            *latest = None;
        }
    }
    if let Ok(mut retired) = PROJECTION_PEER_RETIRED_IDENTITY.lock() {
        *retired = Some((route_generation as u64, decoder_token as u64));
    }
    projection_peer_stop_result(true, false)
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeBindSpatialProjectionPeerDecoder(
    _env: *mut JNIEnv,
    _class: jclass,
    route_generation: jlong,
    decoder_token: jlong,
    reader_generation: jlong,
) -> jlong {
    if route_generation <= 0 || decoder_token <= 0 || reader_generation <= 0 {
        return 0;
    }
    let exact_stream = PROJECTION_PEER_STREAM.lock().ok().is_some_and(|guard| {
        guard.as_ref().is_some_and(|stream| {
            stream.context.role == ProjectionDecoderRole::ProjectionPeer
                && stream.context.route_generation == route_generation as u64
                && stream.context.decoder_token == decoder_token as u64
                && stream.context.reader_generation == reader_generation as u64
        })
    });
    if !exact_stream {
        return 0;
    }
    let Ok(mut binding) = PROJECTION_PEER_BINDING.lock() else {
        return 0;
    };
    *binding = Some((
        route_generation as u64,
        decoder_token as u64,
        reader_generation as u64,
    ));
    let receipt = peer_projection_runtime::record_peer_decoder_bound(
        route_generation,
        decoder_token as u64,
        reader_generation as u64,
    );
    if receipt.words[1] == route_generation
        && receipt.words[2] == peer_projection_runtime::SOURCE_PEER
        && receipt.words[3] == decoder_token
        && receipt.words[4] == reader_generation
        && receipt.words[11] == peer_projection_runtime::RESULT_PENDING
    {
        1
    } else {
        *binding = None;
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialStereoVideoPlayback_nativeStereoVideoLifecycleEvent(
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
    spatial_video_projection_qualification::record_lifecycle(
        event_code,
        result_code,
        width,
        height,
        max_images,
        fps_cap,
    );
    let event_name = match event_code {
        1 => "start-requested",
        2 => "started",
        3 => "stopped",
        4 => "error",
        5 => "format",
        6 => "frame",
        7 => "loop-restarted",
        8 => "decoder-handoff-blocked",
        _ => "unknown",
    };
    log_marker(format!(
        "status={} stream=stereo_video sourceAuthority=android-mediacodec-surface-decoder resultCode={} width={} height={} maxImages={} fpsCap={} looping={} mediaCodecStarted={} decodedFrameEvent={} decoderHandoffBlocked={} decoderOverlapPrevented={} nativeImageReader=true javaHardwareBufferBridge=false rawCamera=false passthroughTexture=false environmentDepth=false geometryWitness=false highRateJsonPayload=false",
        event_name,
        result_code,
        width,
        height,
        max_images,
        fps_cap,
        looping != 0,
        event_code == 2,
        event_code == 6,
        event_code == 8,
        event_code == 8
    ));
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialLaunchQualificationTelemetry_nativeResetQualification(
    _env: *mut JNIEnv,
    _class: jclass,
) {
    spatial_video_projection_qualification::reset();
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialLaunchQualificationTelemetry_nativeDisableQualification(
    _env: *mut JNIEnv,
    _class: jclass,
) {
    spatial_video_projection_qualification::disable();
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialLaunchQualificationTelemetry_nativeReadQualificationField(
    _env: *mut JNIEnv,
    _class: jclass,
    field: jint,
) -> jlong {
    spatial_video_projection_qualification::read_field(field)
}

impl NativeSpatialVideoProjectionStream {
    unsafe fn create_surface(
        env: *mut JNIEnv,
        width: i32,
        height: i32,
        max_images: i32,
        fps_cap: i32,
        decoder_token: u64,
        role: ProjectionDecoderRole,
        route_generation: u64,
        packed_identity_required: bool,
    ) -> Result<(Self, jobject), String> {
        let reader_generation = next_reader_generation()?;
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
            return Err(format!(
                "AImageReader_newWithUsage failed result={result} format=private usage={usage}"
            ));
        }

        let reader_lifetime = Arc::new(SpatialVideoProjectionReaderLifetime { reader });
        let context = Arc::new(NativeSpatialVideoProjectionReaderContext {
            reader_generation,
            decoder_token,
            role,
            route_generation,
            reader_address: reader as usize,
            reader_lifetime: Arc::clone(&reader_lifetime),
            callback_gate: CallbackGate::new(),
            source: Mutex::new(ProjectionFrameSourceState::new_bound(
                reader_generation,
                decoder_token,
                role,
                route_generation,
                packed_identity_required,
            )),
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
        });
        let mut stream = Self {
            window: ptr::null_mut(),
            reader_lifetime,
            context: Arc::clone(&context),
        };
        {
            let mut contexts = SPATIAL_VIDEO_PROJECTION_CONTEXTS
                .lock()
                .map_err(|_| "AImageReader callback registry lock poisoned".to_string())?;
            if contexts
                .insert(reader_generation, Arc::clone(&context))
                .is_some()
            {
                return Err("AImageReader generation collision".to_string());
            }
        }

        let mut listener = AImageReader_ImageListener {
            context: context_token(reader_generation),
            onImageAvailable: Some(spatial_video_projection_on_image_available),
        };
        let listener_status = AImageReader_setImageListener(reader, &mut listener);
        if listener_status != 0 {
            return Err(format!(
                "AImageReader_setImageListener failed result={listener_status}"
            ));
        }

        let mut buffer_listener = AImageReader_BufferRemovedListener {
            context: context_token(reader_generation),
            onBufferRemoved: Some(spatial_video_projection_on_buffer_removed),
        };
        let buffer_listener_status =
            AImageReader_setBufferRemovedListener(reader, &mut buffer_listener);
        log_marker(format!(
            "status={} stream=stereo_video readerGeneration={} cacheEvictionSignal=true nativeImageReader=true",
            if buffer_listener_status == 0 {
                "buffer-removed-listener-registered"
            } else {
                "buffer-removed-listener-error"
            },
            reader_generation
        ));
        if buffer_listener_status != 0 {
            return Err(format!(
                "AImageReader_setBufferRemovedListener failed result={buffer_listener_status}"
            ));
        }

        let mut window = ptr::null_mut();
        let window_result = AImageReader_getWindow(reader, &mut window);
        if window_result != 0 || window.is_null() {
            return Err(format!(
                "AImageReader_getWindow failed result={window_result}"
            ));
        }
        ANativeWindow_acquire(window);
        stream.window = window;

        let surface = ANativeWindow_toSurface(env, window);
        if surface.is_null() {
            return Err("ANativeWindow_toSurface returned null".to_string());
        }

        Ok((stream, surface))
    }
}

unsafe extern "C" fn spatial_video_projection_on_image_available(
    context: *mut c_void,
    reader: *mut AImageReader,
) {
    let Some((reader_context, _permit)) = context_for_callback(context, reader) else {
        return;
    };

    let mut image: *mut AImage = ptr::null_mut();
    let acquire_result = AImageReader_acquireLatestImage(reader, &mut image);
    if acquire_result != 0 || image.is_null() {
        let acquire_error_count = reader_context
            .acquire_errors
            .fetch_add(1, Ordering::Relaxed)
            + 1;
        log_marker(format!(
            "status=acquire-error stream=stereo_video acquireResult={} imageNull={} width={} height={} maxImages={} acquireErrorCount={} imageAcquireApi=AImageReader_acquireLatestImage nativeImageReader=true javaHardwareBufferBridge=false",
            acquire_result,
            image.is_null(),
            reader_context.width,
            reader_context.height,
            reader_context.max_images,
            acquire_error_count
        ));
        return;
    }
    let image_lease = Arc::new(SpatialVideoProjectionImageLease {
        image,
        _reader_lifetime: Arc::clone(&reader_context.reader_lifetime),
    });

    let mut timestamp_ns = 0_i64;
    let timestamp_status = AImage_getTimestamp(image, &mut timestamp_ns);
    if timestamp_status != 0 || timestamp_ns < 0 {
        let acquire_error_count = reader_context
            .acquire_errors
            .fetch_add(1, Ordering::Relaxed)
            + 1;
        log_marker(format!(
            "status=timestamp-error stream=stereo_video readerGeneration={} timestampResult={} timestampNs={} acquireErrorCount={}",
            reader_context.reader_generation,
            timestamp_status,
            timestamp_ns,
            acquire_error_count
        ));
        return;
    }
    let packed_pair = {
        let Ok(mut source) = reader_context.source.lock() else {
            log_marker(format!(
                "status=frame-identity-rejected reason=identity-lock-poisoned readerGeneration={} timestampNs={}",
                reader_context.reader_generation, timestamp_ns
            ));
            return;
        };
        match source.consume_exact(timestamp_ns) {
            Ok(pair) => pair,
            Err(error) => {
                log_marker(format!(
                    "status=frame-identity-rejected reason={:?} readerGeneration={} timestampNs={}",
                    error, reader_context.reader_generation, timestamp_ns
                ));
                return;
            }
        }
    };
    if reader_context.role == ProjectionDecoderRole::ProjectionPeer
        && !projection_peer_binding_matches(
            reader_context.route_generation,
            reader_context.decoder_token,
            reader_context.reader_generation,
        )
    {
        // Drain and release pre-bind output, but never publish it into the fixed Peer slot.
        return;
    }
    if should_drop_for_fps_cap(&reader_context, timestamp_ns) {
        let dropped = reader_context
            .dropped_frames
            .fetch_add(1, Ordering::Relaxed)
            + 1;
        if dropped == 1 || dropped % 60 == 0 {
            log_marker(format!(
                "status=dropped-fps-cap stream=stereo_video droppedFrames={} fpsCap={} timestampNs={} nativeImageReader=true javaHardwareBufferBridge=false",
                dropped, reader_context.fps_cap, timestamp_ns
            ));
        }
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
        log_marker(format!(
            "status=ahardware-buffer-error reason=AImage_getHardwareBuffer stream=stereo_video acquireErrorCount={} timestampNs={} nativeImageReader=true javaHardwareBufferBridge=false",
            acquire_error_count, timestamp_ns
        ));
        return;
    }

    let hardware_buffer = match AndroidHardwareBufferHandle::acquire(hardware_buffer_ptr) {
        Ok(handle) => handle,
        Err(error) => {
            let acquire_error_count = reader_context
                .acquire_errors
                .fetch_add(1, Ordering::Relaxed)
                + 1;
            log_marker(format!(
                "status=ahardware-buffer-error reason={} stream=stereo_video acquireErrorCount={} timestampNs={} nativeImageReader=true javaHardwareBufferBridge=false",
                marker_token(&error),
                acquire_error_count,
                timestamp_ns
            ));
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
    let (removed_hardware_buffer_ids, buffer_reuse_disabled) = match reader_context.source.lock() {
        Ok(source) => source.buffer_removal_snapshot(),
        Err(_) => (Vec::new(), true),
    };
    let frame = SpatialVideoProjectionFrame {
        hardware_buffer,
        descriptor,
        frame_index,
        import_sequence,
        timestamp_ns,
        decoder_token: reader_context.decoder_token,
        reader_generation: reader_context.reader_generation,
        role: reader_context.role,
        route_generation: reader_context.route_generation,
        configured_width: reader_context.width,
        configured_height: reader_context.height,
        max_images: reader_context.max_images,
        fps_cap: reader_context.fps_cap,
        dropped_frames: dropped,
        buffer_removed_count,
        removed_hardware_buffer_ids,
        buffer_reuse_disabled,
        packed_pair,
        image_lease,
    };
    if reader_context.role == ProjectionDecoderRole::CompositorVideo {
        spatial_video_projection_qualification::record_decoded_frame(
            frame.frame_index,
            frame.import_sequence,
            frame.timestamp_ns,
            frame.configured_width,
            frame.configured_height,
            frame.max_images,
            frame.fps_cap,
        );
    }
    let latest_slot = match reader_context.role {
        ProjectionDecoderRole::CompositorVideo => &SPATIAL_VIDEO_PROJECTION_LATEST_FRAME,
        ProjectionDecoderRole::ProjectionPeer => &PROJECTION_PEER_LATEST_FRAME,
    };
    if let Ok(mut latest) = latest_slot.lock() {
        *latest = Some(frame.clone());
    }
    if should_log_spatial_video_projection_frame(frame_index) {
        log_marker(format!(
            "status=decoded-frame-acquired stream=stereo_video frameIndex={} importSequence={} timestampNs={} decoderToken={} readerGeneration={} descriptorWidth={} descriptorHeight={} descriptorLayers={} descriptorFormat={} descriptorUsage={} descriptorStride={} hardwareBufferId={} hardwareBufferIdStatus={} configuredWidth={} configuredHeight={} maxImages={} fpsCap={} droppedFrames={} bufferRemovedCount={} removalBatchCount={} bufferReuseDisabled={} packedStereo={} stereoPairId={} leftSourceFrame={} rightSourceFrame={} leftSensorTimestampNs={} rightSensorTimestampNs={} pairDeltaNs={} receiptSampling=first-and-every-60 imageAcquireApi=AImageReader_acquireLatestImage imageReleaseApi=frame-lease-drop descriptorShape=android-hardware-buffer-private sourceAuthority=android-mediacodec-surface-decoder rawCamera=false passthroughTexture=false environmentDepth=false geometryWitness=false highRateJsonPayload=false nativeImageReader=true nativeImageReaderCount=1 javaHardwareBufferBridge=false cpuPixelCopy=false ahbHandleRetained=true imageLeaseRetained=true latestFramePublished=true videoProjectionGpuImportReady=false videoProjectionGpuAdoptionPath=android-mediacodec-surface-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image",
            frame_index,
            import_sequence,
            timestamp_ns,
            reader_context.decoder_token,
            reader_context.reader_generation,
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
            buffer_removed_count,
            frame.removed_hardware_buffer_ids.len(),
            frame.buffer_reuse_disabled,
            packed_pair.is_some(),
            packed_pair.map(|pair| pair.pair_id).unwrap_or(0),
            packed_pair.map(|pair| pair.left_source_frame).unwrap_or(0),
            packed_pair.map(|pair| pair.right_source_frame).unwrap_or(0),
            packed_pair.map(|pair| pair.left_sensor_timestamp_ns).unwrap_or(0),
            packed_pair.map(|pair| pair.right_sensor_timestamp_ns).unwrap_or(0),
            packed_pair.map(|pair| pair.pair_delta_ns).unwrap_or(0)
        ));
    }
}

unsafe extern "C" fn spatial_video_projection_on_buffer_removed(
    context: *mut c_void,
    reader: *mut AImageReader,
    buffer: *mut ndk_sys::AHardwareBuffer,
) {
    let Some((reader_context, _permit)) = context_for_callback(context, reader) else {
        return;
    };
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
    let stable_buffer_id = descriptor.and_then(|descriptor| {
        (descriptor.hardware_buffer_id_status == 0 && descriptor.hardware_buffer_id != 0)
            .then_some(descriptor.hardware_buffer_id)
    });
    let buffer_reuse_disabled = match reader_context.source.lock() {
        Ok(mut source) => {
            source.record_buffer_removed(stable_buffer_id);
            source.buffer_reuse_disabled()
        }
        Err(_) => true,
    };
    log_marker(format!(
        "status=buffer-removed stream=stereo_video readerGeneration={} removedCount={} hardwareBufferId={} descriptorWidth={} descriptorHeight={} bufferReuseDisabled={} nativeImageReader=true javaHardwareBufferBridge=false cacheEvictionSignal=true",
        reader_context.reader_generation,
        count,
        descriptor.map(|desc| desc.hardware_buffer_id).unwrap_or(0),
        descriptor.map(|desc| desc.width).unwrap_or(0),
        descriptor.map(|desc| desc.height).unwrap_or(0),
        buffer_reuse_disabled
    ));
}

fn should_drop_for_fps_cap(
    reader_context: &NativeSpatialVideoProjectionReaderContext,
    timestamp_ns: i64,
) -> bool {
    if timestamp_ns <= 0 {
        return false;
    }
    let previous = reader_context
        .last_accepted_timestamp_ns
        .load(Ordering::Relaxed);
    if should_drop_spatial_video_projection_timestamp(
        previous,
        timestamp_ns,
        reader_context.fps_cap,
    ) {
        return true;
    }
    reader_context
        .last_accepted_timestamp_ns
        .store(timestamp_ns, Ordering::Relaxed);
    false
}
