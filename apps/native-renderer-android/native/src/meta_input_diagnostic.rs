//! Private, fixed-generation external producer input. No capture authority is
//! granted here: genuine Shell supplies Meta's producer with our owned Surface.
//!
//! This diagnostic deliberately retains exactly one reader/window/context until
//! process exit. Listener removal is not a callback join, and reader deletion
//! invalidates acquired AImages. Never recreating/deleting this reader avoids
//! both races, including shutdown where GPU completion cannot be established.
//! Each acquired AImage is separately retained through the actual GPU fence.
use crate::{
    acamera_sys::*,
    android_hardware_buffer::{AndroidHardwareBufferDescriptor, AndroidHardwareBufferHandle},
    display_composite_native_stream::DisplayCompositeFrame,
    meta_input_diagnostic_state::{Control, WitnessCadence, WitnessTiming},
};
use jni::sys::{JNIEnv, jclass, jint, jlong, jobject};
use std::{
    ptr,
    sync::{Arc, Mutex, OnceLock},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

pub(crate) const WIDTH: i32 = 1280;
pub(crate) const HEIGHT: i32 = 672;
pub(crate) fn layout() -> crate::presentation_geometry::InputLayout {
    crate::presentation_diagnostic::config()
        .map_or(crate::presentation_geometry::InputLayout::MonoLeft, |c| {
            c.layout()
        })
}
pub(crate) fn width() -> i32 {
    crate::presentation_diagnostic::config().map_or(WIDTH, |c| c.input_extent()[0] as i32)
}
pub(crate) fn height() -> i32 {
    crate::presentation_diagnostic::config().map_or(HEIGHT, |c| c.input_height)
}
pub(crate) fn full_core3_available() -> bool {
    crate::full_core_graph::bundle_available()
        && crate::presentation_diagnostic::config()
            .is_some_and(|c| c.has_explicit_stereo_bounds() && c.input_extent() == [2560, 1280])
}
pub(crate) const MAX_IMAGES: i32 = 16;
const MAX_GPU_SLOTS: usize = 12;
const FRESHNESS: Duration = Duration::from_millis(500);
static STREAM: OnceLock<Result<Stream, String>> = OnceLock::new();
static LATEST: Mutex<Option<(Instant, DisplayCompositeFrame)>> = Mutex::new(None);
static WITNESS_DIR: OnceLock<std::path::PathBuf> = OnceLock::new();
static COMPLETED: Mutex<Option<Completion>> = Mutex::new(None);
static WITNESS_MASK: std::sync::atomic::AtomicU8 = std::sync::atomic::AtomicU8::new(0);
static WITNESS_CADENCE: Mutex<WitnessCadence> = Mutex::new(WitnessCadence::new());
static PHASE_CLOCK: OnceLock<Instant> = OnceLock::new();
fn phase_clock_ms() -> u64 {
    PHASE_CLOCK
        .get_or_init(Instant::now)
        .elapsed()
        .as_millis()
        .min(u64::MAX as u128) as u64
}
pub(crate) fn witness_timing(generation: u64, sequence: u64) -> Option<WitnessTiming> {
    WITNESS_CADENCE
        .lock()
        .ok()?
        .ready(generation, sequence, phase_clock_ms())
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct Completion {
    pub(crate) generation: u64,
    pub(crate) sequence: u64,
    pub(crate) mode: u32,
    pub(crate) render_frame: u64,
    pub(crate) input_frame: u64,
    pub(crate) timestamp_ns: i64,
}
pub(crate) fn selection() -> Option<(u32, u64, u64)> {
    let s = stream()?;
    if s.context
        .buffer_removed
        .load(std::sync::atomic::Ordering::Acquire)
    {
        return None;
    }
    let c = s.context.control.lock().ok()?;
    if !c.active {
        return None;
    }
    Some((c.effect.code(), c.generation, c.sequence))
}
pub(crate) fn completed(value: Completion) {
    if let Ok(mut cadence) = WITNESS_CADENCE.lock() {
        cadence.observe(
            value.generation,
            value.sequence,
            value.timestamp_ns,
            phase_clock_ms(),
        );
    }
    if let Ok(mut prior) = COMPLETED.lock() {
        if prior.as_ref().map(|p| p.sequence) != Some(value.sequence) {
            crate::marker(
                "meta-input",
                format!(
                    "status=gpu-completed generation={} mode={} sequence={} renderFrame={} inputFrame={} inputTimestampNs={} hmdPixelProof=false",
                    value.generation,
                    value.mode,
                    value.sequence,
                    value.render_frame,
                    value.input_frame,
                    value.timestamp_ns
                ),
            );
        }
        *prior = Some(value);
    }
}

pub(crate) fn enabled() -> bool {
    !cfg!(feature = "showcase")
        && option_env!("RUSTY_QUEST_NATIVE_RENDERER_META_INPUT_DIAGNOSTIC") == Some("true")
}

struct Stream {
    window: usize,
    context: &'static Context,
}
struct Context {
    acquisition: crate::held_review_policy::AcquisitionGate,
    acquired_images: std::sync::atomic::AtomicUsize,
    buffer_removed: std::sync::atomic::AtomicBool,
    control: Mutex<Control>,
    descriptor: Mutex<Option<AndroidHardwareBufferDescriptor>>,
    frame_count: std::sync::atomic::AtomicU64,
}

#[derive(Debug)]
pub(crate) struct ImageLease {
    image: usize,
    counter: &'static std::sync::atomic::AtomicUsize,
}
unsafe impl Send for ImageLease {}
unsafe impl Sync for ImageLease {}
impl Drop for ImageLease {
    fn drop(&mut self) {
        unsafe { AImage_delete(self.image as *mut AImage) };
        self.counter
            .fetch_sub(1, std::sync::atomic::Ordering::AcqRel);
    }
}

fn stream() -> Option<&'static Stream> {
    STREAM.get()?.as_ref().ok()
}
pub(crate) fn stop() {
    if let Some(s) = stream() {
        if let Ok(mut c) = s.context.control.lock() {
            c.stop();
        }
    }
    let displaced = LATEST.lock().ok().and_then(|mut f| f.take());
    drop(displaced); // AImage_delete runs outside shared locks.
    crate::marker(
        "meta-input",
        "status=disarmed readerRetainedUntilProcessExit=true",
    );
}
/// One-way review seal. Never frees a reader or assumes listener removal joins callbacks.
pub(crate) fn seal_for_review(generation: u64) -> bool {
    let Some(s) = stream() else { return false };
    {
        let Ok(mut c) = s.context.control.lock() else {
            return false;
        };
        // Stop/removal may already have disarmed this immutable reader. Review never rearms it.
        if c.generation != generation {
            return false;
        }
        c.stop();
    }
    s.context.acquisition.seal();
    let displaced = LATEST.lock().ok().and_then(|mut f| f.take());
    drop(displaced);
    true
}
pub(crate) fn acquired_image_count() -> usize {
    stream().map_or(0, |s| {
        s.context
            .acquired_images
            .load(std::sync::atomic::Ordering::Acquire)
    })
}
pub(crate) fn admissible_generation() -> u64 {
    stream()
        .filter(|s| {
            !s.context
                .buffer_removed
                .load(std::sync::atomic::Ordering::Acquire)
        })
        .and_then(|s| {
            s.context
                .control
                .lock()
                .ok()
                .filter(|c| c.active)
                .map(|c| c.generation)
        })
        .unwrap_or(0)
}
pub(crate) fn acquisitions_sealed() -> bool {
    stream().is_some_and(|s| s.context.acquisition.sealed())
}
pub(crate) fn latest_frame() -> Option<DisplayCompositeFrame> {
    let s = stream()?;
    if s.context
        .buffer_removed
        .load(std::sync::atomic::Ordering::Acquire)
    {
        stop();
        return None;
    }
    if !s.context.control.lock().ok()?.active {
        return None;
    }
    LATEST
        .lock()
        .ok()?
        .as_ref()
        .filter(|(at, _)| at.elapsed() <= FRESHNESS)
        .map(|(_, f)| f.clone())
}

pub(crate) fn initialize(gpu_slots: usize) -> Result<(), String> {
    if !enabled() {
        return Ok(());
    }
    if gpu_slots > MAX_GPU_SLOTS {
        return Err("Meta diagnostic GPU slots exceed acquisition budget".into());
    }
    STREAM
        .get_or_init(|| unsafe { create() })
        .as_ref()
        .map(|_| ())
        .map_err(Clone::clone)
}

pub(crate) struct PublisherGuard<'a> {
    app: &'a android_activity::AndroidApp,
}
impl Drop for PublisherGuard<'_> {
    fn drop(&mut self) {
        if let Err(e) = java_lifecycle(self.app, false) {
            crate::marker(
                "meta-input",
                format!("status=unbind-error reason={}", crate::sanitize(&e)),
            );
        }
        stop();
    }
}
pub(crate) fn publish(
    app: &android_activity::AndroidApp,
    gpu_slots: usize,
) -> Result<Option<PublisherGuard<'_>>, String> {
    if !enabled() {
        return Ok(None);
    }
    initialize(gpu_slots)?;
    let root = app
        .internal_data_path()
        .ok_or("Meta diagnostic has no app-private data directory")?;
    let generation = stream()
        .ok_or("Meta diagnostic reader missing")?
        .context
        .control
        .lock()
        .map_err(|_| "Meta control poisoned")?
        .generation;
    crate::held_output_review::initialize(&root, generation)?;
    let dir = root.join("meta-input-witness").join(generation.to_string());
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    WITNESS_DIR
        .set(dir)
        .map_err(|_| "Meta diagnostic cannot republish a retired generation")?;
    java_lifecycle(app, true)?;
    Ok(Some(PublisherGuard { app }))
}
fn java_lifecycle(app: &android_activity::AndroidApp, start: bool) -> Result<(), String> {
    use jni::{
        JavaVM, jni_sig, jni_str,
        objects::{JClass, JClassLoader, JObject, JValue},
    };
    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let raw_activity = app.activity_as_ptr() as jobject;
    vm.attach_current_thread(|env| -> jni::errors::Result<()> {
        let activity = unsafe { env.as_cast_raw::<JObject>(&raw_activity)? };
        let loader = env
            .call_method(
                &activity,
                jni_str!("getClassLoader"),
                jni_sig!("()Ljava/lang/ClassLoader;"),
                &[],
            )?
            .l()?;
        let loader: JClassLoader = env.cast_local::<JClassLoader>(loader)?;
        let name = env.new_string("io.github.mesmerprism.rustyquest.NativeMetaInputDiagnostic")?;
        let class = JClass::for_name_with_loader(env, name, true, loader)?;
        if start {
            env.call_static_method(
                class,
                jni_str!("start"),
                jni_sig!("(Landroid/content/Context;)V"),
                &[JValue::Object(&activity)],
            )?;
        } else {
            env.call_static_method(
                class,
                jni_str!("stop"),
                jni_sig!("(Landroid/content/Context;)V"),
                &[JValue::Object(&activity)],
            )?;
        }
        Ok(())
    })
    .map_err(|e| format!("Meta diagnostic own-service lifecycle failed: {e}"))
}

/// Join only the original Java cleanup action, without holding POLICY or callback locks.
/// Pending/rejected/failed/unknown cleanup never authorizes a receipt.
pub(crate) fn join_java_cleanup(
    app: &android_activity::AndroidApp,
) -> crate::held_review_policy::JavaCleanup {
    use crate::held_review_policy::JavaCleanup;
    use jni::{
        JavaVM, jni_sig, jni_str,
        objects::{JClass, JClassLoader, JObject},
    };
    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let raw = app.activity_as_ptr() as jobject;
    let result = vm.attach_current_thread(|env| -> jni::errors::Result<i32> {
        let activity = unsafe { env.as_cast_raw::<JObject>(&raw)? };
        let loader = env
            .call_method(
                &activity,
                jni_str!("getClassLoader"),
                jni_sig!("()Ljava/lang/ClassLoader;"),
                &[],
            )?
            .l()?;
        let loader: JClassLoader = env.cast_local::<JClassLoader>(loader)?;
        let name = env.new_string("io.github.mesmerprism.rustyquest.NativeMetaInputDiagnostic")?;
        let class = JClass::for_name_with_loader(env, name, true, loader)?;
        env.call_static_method(
            class,
            jni_str!("awaitHeldReviewCleanup"),
            jni_sig!("()I"),
            &[],
        )?
        .i()
    });
    match result {
        Ok(0) => JavaCleanup::NotAttempted,
        Ok(1) => JavaCleanup::Pending,
        Ok(2) => JavaCleanup::Complete,
        _ => JavaCleanup::Unknown,
    }
}

unsafe fn create() -> Result<Stream, String> {
    let generation = (SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|e| e.to_string())?
        .as_nanos()
        % (i64::MAX as u128)) as u64;
    let context = Box::leak(Box::new(Context {
        acquisition: crate::held_review_policy::AcquisitionGate::default(),
        acquired_images: std::sync::atomic::AtomicUsize::new(0),
        buffer_removed: std::sync::atomic::AtomicBool::new(false),
        control: Mutex::new(Control::new_with_full_core3(
            generation.max(1),
            full_core3_available(),
        )),
        descriptor: Mutex::new(None),
        frame_count: std::sync::atomic::AtomicU64::new(0),
    }));
    let mut reader = ptr::null_mut();
    let usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    if AImageReader_newWithUsage(
        width(),
        height(),
        AIMAGE_FORMAT_RGBA_8888,
        usage,
        MAX_IMAGES,
        &mut reader,
    ) != 0
        || reader.is_null()
    {
        return Err("Meta diagnostic AImageReader allocation failed".into());
    }
    // From the first listener registration onward this bounded reader and its
    // tombstone context belong to process lifetime, including partial failures.
    let mut window = ptr::null_mut();
    if AImageReader_getWindow(reader, &mut window) != 0 || window.is_null() {
        AImageReader_delete(reader);
        return Err("Meta diagnostic reader window unavailable".into());
    }
    ANativeWindow_acquire(window);
    let mut listener = AImageReader_ImageListener {
        context: (context as *mut Context).cast(),
        onImageAvailable: Some(on_image),
    };
    let mut removed = AImageReader_BufferRemovedListener {
        context: (context as *mut Context).cast(),
        onBufferRemoved: Some(on_removed),
    };
    if AImageReader_setImageListener(reader, &mut listener) != 0
        || AImageReader_setBufferRemovedListener(reader, &mut removed) != 0
    {
        context
            .control
            .lock()
            .map_err(|_| "Meta control poisoned")?
            .stop();
        return Err("Meta diagnostic listener registration failed; reader retained".into());
    }
    crate::marker(
        "meta-input",
        format!(
            "status=reader-created generation={} width={} height={} maxImages={} layout={} fullCore3Available={} sourceAuthority=external-meta-producer producerAdmission=pending cpuPixelCopy=false readerLifetime=process",
            generation,
            width(),
            height(),
            MAX_IMAGES,
            layout().name(),
            full_core3_available()
        ),
    );
    Ok(Stream {
        window: window as usize,
        context,
    })
}

unsafe extern "C" fn on_image(context: *mut std::os::raw::c_void, reader: *mut AImageReader) {
    // The context is an intentionally process-lifetime tombstone. It remains
    // valid for late callbacks after disarm; no callback touches a freed reader.
    let Some(c) = (context as *const Context).as_ref() else {
        return;
    };
    c.acquisition.run(|| on_image_admitted(c, reader));
}
unsafe fn on_image_admitted(c: &'static Context, reader: *mut AImageReader) {
    if c.buffer_removed.load(std::sync::atomic::Ordering::Acquire) {
        return;
    }
    let mut image = ptr::null_mut();
    if AImageReader_acquireLatestImage(reader, &mut image) != 0 || image.is_null() {
        return;
    }
    let lease = Arc::new(ImageLease {
        image: image as usize,
        counter: &c.acquired_images,
    });
    c.acquired_images
        .fetch_add(1, std::sync::atomic::Ordering::AcqRel);
    // Continue draining after disarm so the external producer cannot deadlock
    // on a full queue while Root retires its own capture session.
    if !c.control.lock().map(|c| c.active).unwrap_or(false) {
        return;
    }
    let mut ahb = ptr::null_mut();
    let mut timestamp = 0;
    if AImage_getHardwareBuffer(image, &mut ahb) != 0
        || AImage_getTimestamp(image, &mut timestamp) != 0
        || timestamp <= 0
    {
        return;
    }
    let Ok(buffer) = AndroidHardwareBufferHandle::acquire(ahb) else {
        return;
    };
    let d = buffer.descriptor();
    if d.hardware_buffer_id == 0
        || d.hardware_buffer_id_status != 0
        || d.width != width() as u32
        || d.height != height() as u32
        || d.layers != 1
        || d.format != AIMAGE_FORMAT_RGBA_8888 as u32
        || d.stride < d.width
        || d.usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE == 0
    {
        if let Ok(mut ctl) = c.control.lock() {
            ctl.stop();
        }
        crate::marker(
            "meta-input",
            "status=disarmed reason=invalid-ahb-descriptor",
        );
        return;
    }
    let Ok(mut fixed) = c.descriptor.lock() else {
        return;
    };
    if let Some(prior) = *fixed {
        if (
            prior.width,
            prior.height,
            prior.layers,
            prior.format,
            prior.usage,
            prior.stride,
        ) != (d.width, d.height, d.layers, d.format, d.usage, d.stride)
        {
            if let Ok(mut ctl) = c.control.lock() {
                ctl.stop();
            }
            crate::marker(
                "meta-input",
                "status=disarmed reason=immutable-descriptor-changed",
            );
            return;
        }
    } else {
        *fixed = Some(d);
    }
    drop(fixed);
    let Ok(ctl) = c.control.lock() else {
        return;
    };
    if !ctl.active || c.buffer_removed.load(std::sync::atomic::Ordering::Acquire) {
        return;
    }
    let count = c
        .frame_count
        .fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        + 1;
    let frame = DisplayCompositeFrame {
        hardware_buffer: buffer,
        descriptor: d,
        frame_index: count,
        import_sequence: count,
        timestamp_ns: timestamp,
        configured_width: width(),
        configured_height: height(),
        max_images: MAX_IMAGES,
        fps_cap: 90,
        dropped_frames: 0,
        buffer_removed_count: 0,
        source_generation: ctl.generation,
        image_lease: Some(lease),
    };
    let generation = ctl.generation;
    drop(ctl);
    let displaced = LATEST
        .lock()
        .ok()
        .and_then(|mut f| f.replace((Instant::now(), frame)));
    drop(displaced);
    if count == 1 || count % 120 == 0 {
        crate::marker(
            "meta-input",
            format!(
                "status=frame generation={} frame={} timestampNs={} hardwareBufferId={} width={} height={} format={} usage={} stride={} imageLeaseRetained=true sourceAuthority=external-meta-producer producerIdentityVerified=false dataspace=producer-default-unverified",
                generation,
                count,
                timestamp,
                d.hardware_buffer_id,
                d.width,
                d.height,
                d.format,
                d.usage,
                d.stride
            ),
        );
    }
}

/// Called only after the renderer's proven frame fence and durable exports of
/// both raw-pack inputs AND both eye-sized FullCore3 results from the same
/// retained AImage/submission. Admission or submission alone is insufficient.
pub(crate) fn full_core3_witness_completed(generation: u64, sequence: u64) {
    if let Some(s) = stream() {
        if let Ok(c) = s.context.control.lock() {
            if c.active
                && c.generation == generation
                && c.sequence == sequence
                && c.effect.code() == 2
            {
                WITNESS_MASK.fetch_or(1 << 2, std::sync::atomic::Ordering::Release);
            }
        }
    }
}

/// Raw input as stored by the verified private pack pass, before guide/effect
/// processing. Rows retain the pack's canonical bottom-left domain; alpha is
/// preserved, including zero alpha with nonzero sampled RGB. No display proof.
pub(crate) fn write_full_core3_paired_raw(
    completion: Completion,
    timing: WitnessTiming,
    eye: usize,
    metadata: &serde_json::Value,
    bytes: &[u8],
) -> Result<(), String> {
    if completion.mode != 2 || eye > 1 || bytes.len() != 1280 * 1280 * 4 {
        return Err("Invalid FullCore3 paired raw export".into());
    }
    let dir = WITNESS_DIR
        .get()
        .ok_or("Meta diagnostic witness directory missing")?;
    let stem = format!("full-core3-input-eye-{eye}-seq-{}", completion.sequence);
    let write = |path: &std::path::Path, data: &[u8]| -> Result<(), String> {
        use std::io::Write;
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(path)
            .map_err(|e| e.to_string())?;
        file.write_all(data).map_err(|e| e.to_string())?;
        file.sync_all().map_err(|e| e.to_string())
    };
    write(&dir.join(format!("{stem}.rgba")), bytes)?;
    let mut m = metadata.clone();
    m["schema"] = "private.meta_input.paired_raw_pack_witness.v1".into();
    m["source_stage"] = "raw-pack-atlas-pass0".into();
    m["generation"] = completion.generation.into();
    m["effect_mode"] = completion.mode.into();
    m["sequence"] = completion.sequence.into();
    m["input_timestamp_ns"] = completion.timestamp_ns.into();
    m["source_frame"] = completion.input_frame.into();
    m["render_frame"] = completion.render_frame.into();
    m["eye"] = eye.into();
    m["width"] = 1280.into();
    m["height"] = 1280.into();
    m["vk_format"] = ash::vk::Format::R8G8B8A8_UNORM.as_raw().into();
    m["byte_count"] = bytes.len().into();
    m["row_orientation"] = "canonical-bottom-left-effect-domain".into();
    m["alpha"] = "sampled-input-alpha-preserved-including-zero-alpha-rgb".into();
    m["witness"] = "same-retained-aimage-raw-pack-region-after-original-frame-fence".into();
    m["steady_phase_elapsed_ms"] = timing.elapsed_ms.into();
    m["steady_completed_input_advances"] = timing.input_advances.into();
    m["paired_processed_file"] = format!(
        "presentation-witness/{}/eye-{eye}-mode-2-seq-{}.rgba",
        completion.generation, completion.sequence
    )
    .into();
    write(&dir.join(format!("{stem}.json")), m.to_string().as_bytes())?;
    Ok(())
}

pub(crate) fn write_witness(
    mode: u32,
    generation: u64,
    sequence: u64,
    raw_input: bool,
    timing: WitnessTiming,
    timestamp_ns: i64,
    source_frame: u64,
    render_frame: u64,
    width: u32,
    height: u32,
    format: ash::vk::Format,
    bytes: &[u8],
) -> Result<(), String> {
    let dir = WITNESS_DIR
        .get()
        .ok_or("Meta diagnostic witness directory missing")?;
    let name = match (mode, raw_input) {
        (0, false) => "raw",
        (1, false) => "colorama",
        (1, true) => "colorama-input",
        (2, false) => "full-core3",
        (2, true) => "full-core3-input",
        _ => return Err("Invalid witness mode/pair".into()),
    };
    let file_stem = format!("{name}-seq-{sequence}");
    std::fs::write(dir.join(format!("{file_stem}.rgba")), bytes).map_err(|e| e.to_string())?;
    let metadata = serde_json::json!({"schema":"private.meta_input.shader_witness.v1", "generation":generation,
        "mode":name, "mode_sequence":sequence, "paired_raw_input":raw_input, "input_timestamp_ns":timestamp_ns, "source_frame":source_frame, "render_frame":render_frame,
        "steady_phase_elapsed_ms":timing.elapsed_ms,"steady_completed_input_advances":timing.input_advances,"first_completed_input_timestamp_ns":timing.first_timestamp_ns,
        "width":width,"height":height,"vk_format":format.as_raw(),"byte_count":bytes.len(),
        "witness":"same-pipeline-offscreen-shader-result-after-proven-frame-fence", "hmd_pixel_proof":false,
        "input_layout":layout().name(),"source_uv":if mode==2 {"explicit-stereo-layout"} else {"unit"},"source_capture_bounds_qualified":false,"target_rect":"unit","cpu_readback":"once-per-control-sequence-max-four"});
    std::fs::write(dir.join(format!("{file_stem}.json")), metadata.to_string())
        .map_err(|e| e.to_string())?;
    if !raw_input {
        if let Some(s) = stream() {
            if let Ok(c) = s.context.control.lock() {
                if c.generation == generation && c.sequence == sequence {
                    WITNESS_MASK.fetch_or(1 << mode, std::sync::atomic::Ordering::Release);
                }
            }
        }
    }
    crate::marker(
        "meta-input-witness",
        format!(
            "status=written generation={} mode={} sourceTimestampNs={} sourceFrame={} renderFrame={} width={} height={} vkFormat={:?} bytes={} hmdPixelProof=false",
            generation,
            name,
            timestamp_ns,
            source_frame,
            render_frame,
            width,
            height,
            format,
            bytes.len()
        ),
    );
    Ok(())
}

#[cfg(not(feature = "showcase"))]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_NativeMetaInputDiagnostic_nativeGetRenderStatus(
    mut env: jni::EnvUnowned,
    _class: jclass,
    generation: jlong,
) -> jni::sys::jlongArray {
    if !enabled() || generation <= 0 {
        return ptr::null_mut();
    }
    let Some(s) = stream() else {
        return ptr::null_mut();
    };
    if s.context
        .buffer_removed
        .load(std::sync::atomic::Ordering::Acquire)
    {
        return ptr::null_mut();
    }
    let Ok(c) = s.context.control.lock() else {
        return ptr::null_mut();
    };
    if generation as u64 != c.generation || !c.active {
        return ptr::null_mut();
    }
    let prior = COMPLETED.lock().ok().and_then(|p| *p);
    let values = [
        generation,
        c.sequence as i64,
        i64::from(c.effect.code()),
        prior.map_or(0, |p| p.sequence as i64),
        prior.map_or(0, |p| p.mode as i64),
        prior.map_or(0, |p| p.render_frame as i64),
        prior.map_or(0, |p| p.input_frame as i64),
        prior.map_or(0, |p| p.timestamp_ns),
        WITNESS_MASK.load(std::sync::atomic::Ordering::Acquire) as i64,
        i64::from(prior.is_some()),
    ];
    drop(c);
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jlongArray> {
            let a = env.new_long_array(values.len())?;
            a.set_region(env, 0, &values)?;
            Ok(a.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(a) => a,
        _ => ptr::null_mut(),
    }
}

unsafe extern "C" fn on_removed(
    context: *mut std::os::raw::c_void,
    _reader: *mut AImageReader,
    _buffer: *mut ndk_sys::AHardwareBuffer,
) {
    if let Some(c) = (context as *const Context).as_ref() {
        // This callback may be reentrant from AImage_delete. Never wait for the
        // acquisition gate or drop another image here; all admission checks see this tombstone.
        c.buffer_removed
            .store(true, std::sync::atomic::Ordering::Release);
        crate::marker(
            "meta-input",
            "status=disarmed reason=buffer-removed immutableGeneration=true",
        );
    }
}

#[cfg(not(feature = "showcase"))]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_NativeMetaInputDiagnostic_nativeGetGeneration(
    _env: *mut JNIEnv,
    _class: jclass,
) -> jlong {
    if !enabled() {
        return 0;
    }
    admissible_generation() as jlong
}
#[cfg(not(feature = "showcase"))]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_NativeMetaInputDiagnostic_nativeAcquireSurface(
    env: *mut JNIEnv,
    _class: jclass,
    generation: jlong,
) -> jobject {
    if !enabled() || env.is_null() {
        return ptr::null_mut();
    }
    let Some(s) = stream() else {
        return ptr::null_mut();
    };
    let Ok(c) = s.context.control.lock() else {
        return ptr::null_mut();
    };
    if !c.active
        || s.context
            .buffer_removed
            .load(std::sync::atomic::Ordering::Acquire)
        || generation <= 0
        || generation as u64 != c.generation
    {
        return ptr::null_mut();
    }
    unsafe { ANativeWindow_toSurface(env, s.window as *mut ANativeWindow) }
}
#[cfg(not(feature = "showcase"))]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_NativeMetaInputDiagnostic_nativeSetMode(
    _env: *mut JNIEnv,
    _class: jclass,
    generation: jlong,
    mode: jint,
    sequence: jlong,
) -> u8 {
    if !enabled() || generation <= 0 || sequence <= 0 {
        return 0;
    }
    if mode == 2 && !full_core3_available() {
        return 0;
    }
    stream()
        .and_then(|s| {
            s.context.control.lock().ok().map(|mut c| {
                if s.context
                    .buffer_removed
                    .load(std::sync::atomic::Ordering::Acquire)
                {
                    return false;
                }
                let accepted = c.select(generation as u64, mode, sequence as u64);
                if accepted {
                    WITNESS_MASK.store(0, std::sync::atomic::Ordering::Release);
                }
                accepted
            })
        })
        .unwrap_or(false) as u8
}
#[cfg(not(feature = "showcase"))]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_NativeMetaInputDiagnostic_nativeGetDescriptor(
    mut env: jni::EnvUnowned,
    _class: jclass,
    generation: jlong,
) -> jni::sys::jlongArray {
    if !enabled()
        || generation <= 0
        || Java_io_github_mesmerprism_rustyquest_NativeMetaInputDiagnostic_nativeGetGeneration(
            ptr::null_mut(),
            ptr::null_mut(),
        ) != generation
    {
        return ptr::null_mut();
    }
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jlongArray> {
            let a = env.new_long_array(5)?;
            a.set_region(
                env,
                0,
                &[
                    width() as i64,
                    height() as i64,
                    AIMAGE_FORMAT_RGBA_8888 as i64,
                    (AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
                        | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT) as i64,
                    MAX_IMAGES as i64,
                ],
            )?;
            Ok(a.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        _ => ptr::null_mut(),
    }
}
