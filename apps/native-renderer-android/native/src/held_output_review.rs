//! Debug-only, one-way stopped-source review. Shell attests its actors; this app proves its own drain.
use crate::held_review_policy::{
    self as policy, Drain, ExternalRetirement, Eye, Pair, Policy, State,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
};

static ROOT: OnceLock<PathBuf> = OnceLock::new();
static POLICY: Mutex<Option<Policy>> = Mutex::new(None);
static REQUEST: OnceLock<Request> = OnceLock::new();

pub(crate) fn enabled() -> bool {
    // Diagnostic APKs use optimized native code; Java additionally requires FLAG_DEBUGGABLE.
    !cfg!(feature = "showcase")
        && option_env!("RUSTY_QUEST_NATIVE_RENDERER_HELD_OUTPUT_REVIEW") == Some("true")
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Request {
    schema: String,
    generation: u64,
    app_pid: u32,
    app_uid: u32,
    app_start_ticks: u64,
    app_apk_sha256: String,
    canonical: String,
    boot_id: String,
    lease_id: String,
    capture_session_id: String,
    capture_nonce: String,
    helper_pid: u32,
    helper_start_ticks: u64,
    controller_pid: u32,
    controller_start_ticks: u64,
    external_receipt_sha256: String,
    accepted_stop: bool,
    no_pending_rpc: bool,
    helper_eof_reaped_absent: bool,
    controller_eof_reaped_absent: bool,
}

pub(crate) fn initialize(root: &Path, generation: u64) -> Result<(), String> {
    if !enabled() {
        return Ok(());
    }
    ROOT.set(root.to_owned())
        .map_err(|_| "Review cannot initialize twice")?;
    *POLICY.lock().map_err(|_| "Review state poisoned")? = Some(Policy::new(generation));
    Ok(())
}
fn hex(value: &str, n: usize) -> bool {
    value.len() == n
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn uuid(value: &str) -> bool {
    value.len() == 36
        && value.bytes().enumerate().all(|(i, b)| {
            if [8, 13, 18, 23].contains(&i) {
                b == b'-'
            } else {
                b.is_ascii_hexdigit()
            }
        })
}
fn own_birth() -> Option<(u32, u64)> {
    let s = fs::read_to_string("/proc/self/stat").ok()?;
    let (pid, _) = s.split_once(' ')?;
    let tail = s.rsplit_once(") ")?.1;
    Some((
        pid.parse().ok()?,
        tail.split_whitespace().nth(19)?.parse().ok()?,
    ))
}
fn request(raw: &str) -> bool {
    if !enabled() || raw.len() > 4096 {
        return false;
    }
    let Ok(r) = serde_json::from_str::<Request>(raw) else {
        return false;
    };
    if r.schema != "rusty.quest.held_review.source_retirement.v1"
        || own_birth() != Some((r.app_pid, r.app_start_ticks))
        || r.app_uid == 0
        || !policy::digest(&r.app_apk_sha256)
        || !policy::digest(&r.external_receipt_sha256)
        || r.canonical.is_empty()
        || r.canonical.len() > 64
        || !r.canonical.bytes().all(|c| c.is_ascii_alphanumeric())
        || !uuid(&r.boot_id)
        || !uuid(&r.lease_id)
        || !hex(&r.capture_session_id, 32)
        || !hex(&r.capture_nonce, 32)
        || r.helper_pid == 0
        || r.helper_start_ticks == 0
        || r.controller_pid == 0
        || r.controller_start_ticks == 0
    {
        return false;
    }
    let e = ExternalRetirement {
        receipt_sha256: r.external_receipt_sha256.clone(),
        accepted_stop: r.accepted_stop,
        no_pending_rpc: r.no_pending_rpc,
        helper_eof_reaped_absent: r.helper_eof_reaped_absent,
        controller_eof_reaped_absent: r.controller_eof_reaped_absent,
    };
    let Ok(mut state) = POLICY.lock() else {
        return false;
    };
    let Some(p) = state.as_mut() else {
        return false;
    };
    if !p.begin(r.generation, &e) {
        return false;
    }
    if REQUEST.set(r.clone()).is_err()
        || !crate::meta_input_diagnostic::seal_for_review(r.generation)
    {
        p.state = State::Held;
        return false;
    }
    crate::marker(
        "held-review",
        format!(
            "status=sink-sealed generation={} sourceStopAuthority=root-shell-attestation appDrain=pending nativeRetirementAck=false liveFreshness=false",
            r.generation
        ),
    );
    true
}
pub(crate) fn requested() -> bool {
    POLICY
        .lock()
        .ok()
        .and_then(|p| p.as_ref().map(|p| p.state == State::Retiring))
        .unwrap_or(false)
}
/// The same mutex serializes request admission with the outer owner's teardown decision.
/// Call once before any resource destruction; the returned decision cannot be reopened.
pub(crate) fn begin_teardown() -> bool {
    POLICY
        .lock()
        .ok()
        .and_then(|mut p| p.as_mut().map(Policy::begin_teardown))
        .unwrap_or(false)
}
pub(crate) fn hold(reason: &str) {
    if let Ok(mut p) = POLICY.lock() {
        if let Some(p) = p.as_mut() {
            p.state = State::Held;
        }
    }
    crate::marker(
        "held-review",
        format!(
            "status=held reason={} appDrainProven=false liveFreshness=false",
            crate::sanitize(reason)
        ),
    );
}
fn status() -> String {
    let (state, admissible_generation) = POLICY
        .lock()
        .ok()
        .and_then(|p| {
            p.as_ref()
                .map(|p| (format!("{:?}", p.state), p.admissible_generation()))
        })
        .unwrap_or_else(|| ("Disabled".into(), 0));
    let admissible_generation =
        if admissible_generation == crate::meta_input_diagnostic::admissible_generation() {
            admissible_generation
        } else {
            0
        };
    serde_json::json!({"schema":"rusty.quest.held_review.status.v1","state":state,"generation":REQUEST.get().map_or(0,|r|r.generation),"admissible_generation":admissible_generation,"live_freshness":false,"native_retirement_acknowledged":false}).to_string()
}
fn hash(path: &Path) -> Result<(String, u64), String> {
    let mut f = fs::File::open(path).map_err(|e| e.to_string())?;
    let mut h = Sha256::new();
    let mut n = 0;
    let mut b = [0u8; 65536];
    loop {
        let r = f.read(&mut b).map_err(|e| e.to_string())?;
        if r == 0 {
            break;
        }
        n += r as u64;
        if n > 4096 * 4096 * 4 {
            return Err("Review file too large".into());
        }
        h.update(&b[..r]);
    }
    Ok((format!("{:x}", h.finalize()), n))
}
fn number(v: &serde_json::Value, key: &str) -> Result<u64, String> {
    v[key]
        .as_u64()
        .ok_or_else(|| format!("Missing integer {key}"))
}
fn read_json(path: &Path) -> Result<serde_json::Value, String> {
    let mut raw = Vec::new();
    fs::File::open(path)
        .map_err(|e| e.to_string())?
        .take(16385)
        .read_to_end(&mut raw)
        .map_err(|e| e.to_string())?;
    if raw.len() > 16384 {
        return Err("Review metadata too large".into());
    }
    serde_json::from_slice(&raw).map_err(|e| e.to_string())
}
fn output_pair(root: &Path, g: u64) -> Result<(Pair, serde_json::Value), String> {
    let mut eyes = Vec::new();
    let mut records = Vec::new();
    for eye in 0..2 {
        let stem = format!("presentation-witness/{g}/eye-{eye}-mode-2-seq-2");
        let m = read_json(&root.join(format!("{stem}.json")))?;
        if m["paired_same_aimage"] != true
            || m["paired_same_gpu_submission"] != true
            || number(&m, "effect_mode")? != 2
        {
            return Err("Completed paired output required".into());
        }
        let raw_stem = format!("meta-input-witness/{g}/full-core3-input-eye-{eye}-seq-2");
        let raw = read_json(&root.join(format!("{raw_stem}.json")))?;
        for k in [
            "generation",
            "sequence",
            "eye",
            "input_timestamp_ns",
            "source_frame",
            "render_frame",
        ] {
            if m[k] != raw[k] {
                return Err(format!("Mixed output/raw {k}"));
            }
        }
        if number(&raw, "width")? != 1280
            || number(&raw, "height")? != 1280
            || number(&raw, "effect_mode")? != 2
        {
            return Err("Raw pack shape".into());
        }
        let (raw_sha, raw_bytes) = hash(&root.join(format!("{raw_stem}.rgba")))?;
        if raw_bytes != 1280 * 1280 * 4 || number(&raw, "byte_count")? != raw_bytes {
            return Err("Raw bytes".into());
        }
        let (sha, bytes) = hash(&root.join(format!("{stem}.rgba")))?;
        let e = Eye {
            generation: number(&m, "generation")?,
            sequence: number(&m, "sequence")?,
            eye: number(&m, "eye")? as usize,
            source_frame: number(&m, "source_frame")?,
            timestamp_ns: m["input_timestamp_ns"].as_i64().ok_or("Timestamp")?,
            render_frame: number(&m, "render_frame")?,
            width: m["eye_size"][0]
                .as_u64()
                .filter(|n| *n <= 4096)
                .ok_or("Width")? as u32,
            height: m["eye_size"][1]
                .as_u64()
                .filter(|n| *n <= 4096)
                .ok_or("Height")? as u32,
            format: m["vk_format"].as_i64().ok_or("Format")? as i32,
            bytes,
            sha256: sha.clone(),
        };
        if number(&m, "byte_count")? != bytes || e.generation != g {
            return Err("Output bytes/generation".into());
        }
        records.push(serde_json::json!({"eye":eye,"file":format!("{stem}.rgba"),"sha256":sha,"bytes":bytes,"width":e.width,"height":e.height,"vk_format":e.format,"raw_sha256":raw_sha,"source_frame":e.source_frame,"source_timestamp_ns":e.timestamp_ns,"render_frame":e.render_frame}));
        eyes.push(e);
    }
    let pair = Pair::checked(eyes).ok_or("Mixed/incomplete processed eye pair")?;
    Ok((
        pair,
        serde_json::json!({"schema":"rusty.quest.held_review.output.v1","generation":g,"sequence":2,"mode":2,"eyes":records,"live_freshness":false}),
    ))
}
fn write_once(path: &Path, value: &serde_json::Value) -> Result<(), String> {
    let parent = path.parent().ok_or("No receipt parent")?;
    fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    if path.exists() {
        return Err("Receipt already exists".into());
    }
    let pending = path.with_extension("pending");
    let mut f = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&pending)
        .map_err(|e| e.to_string())?;
    f.write_all(value.to_string().as_bytes())
        .and_then(|_| f.sync_all())
        .map_err(|e| e.to_string())?;
    fs::rename(&pending, path).map_err(|e| e.to_string())
}
pub(crate) fn complete(app: &android_activity::AndroidApp, mut drain: Drain) -> Result<(), String> {
    // Never hold POLICY while joining Java's original main-queue action. Its native
    // status/disarm calls can acquire POLICY and callback locks on that queue.
    drain.java_cleanup = crate::meta_input_diagnostic::join_java_cleanup(app);
    let r = REQUEST.get().ok_or("No source retirement request")?;
    let root = ROOT.get().ok_or("No review root")?;
    let result = output_pair(root, r.generation);
    let mut state = POLICY.lock().map_err(|_| "Review state poisoned")?;
    let p = state.as_mut().ok_or("No policy")?;
    let ready = p.finish(drain, result.as_ref().ok().map(|x| &x.0));
    if !ready {
        return Err("App drain/output pair not proven".into());
    }
    let (_, output) = result?;
    let receipt = serde_json::json!({"schema":"rusty.quest.held_review.app_drain.v1","generation":r.generation,"ready":true,
        "external_authority":"root-shell-stop-and-original-helper-controller-retirement-attestation","external":r,
        "app_observed":{"java_publisher_cleanup":"Complete","gpu_idle":drain.gpu_idle,"recordings_reset":drain.recordings_reset,"resource_touching_callbacks_sealed":drain.callbacks_sealed,"acquired_aimages":drain.acquired_images,"source_image_refs":drain.image_refs,"source_imports":drain.imports,"pending_exports":drain.pending_exports,"pending_foreign_releases":drain.pending_releases},
        "reader_tombstone_retained":true,"future_acquisition_allowed":false,"native_retirement_acknowledged":false,"live_freshness":false,"output":output});
    if let Err(e) = write_once(
        &root.join(format!("held-review/{}/retirement.json", r.generation)),
        &receipt,
    ) {
        p.state = State::Held;
        return Err(e);
    }
    crate::marker(
        "held-review",
        format!(
            "status=app-drained generation={} sourceStopped=operator-attested acquiredAImages=0 imports=0 liveFreshness=false nativeRetirementAck=false",
            r.generation
        ),
    );
    Ok(())
}

pub(crate) fn ready() -> bool {
    POLICY
        .lock()
        .ok()
        .and_then(|p| p.as_ref().map(|p| p.state == State::Ready))
        .unwrap_or(false)
}
pub(crate) fn show_ready(
    app: &android_activity::AndroidApp,
    owner: &crate::fullview_passthrough_diagnostic::DiagnosticOwner,
) -> Result<(), String> {
    if !owner.can_review() {
        return Ok(());
    }
    let ready = POLICY
        .lock()
        .ok()
        .and_then(|p| p.as_ref().map(|p| p.state == State::Ready))
        .unwrap_or(false);
    if !ready {
        return Ok(());
    }
    use jni::{
        JavaVM, jni_sig, jni_str,
        objects::{JClass, JClassLoader, JObject},
        sys::jobject,
    };
    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let activity = app.activity_as_ptr() as jobject;
    vm.attach_current_thread(|env| -> jni::errors::Result<()> {
        let object = unsafe { env.as_cast_raw::<JObject>(&activity)? };
        let loader = env
            .call_method(
                &object,
                jni_str!("getClassLoader"),
                jni_sig!("()Ljava/lang/ClassLoader;"),
                &[],
            )?
            .l()?;
        let loader: JClassLoader = env.cast_local::<JClassLoader>(loader)?;
        let name = env.new_string("io.github.mesmerprism.rustyquest.NativeHeldReviewActivity")?;
        let class = JClass::for_name_with_loader(env, name, true, loader)?;
        env.call_static_method(
            class,
            jni_str!("showAfterNativeDrain"),
            jni_sig!("(Landroid/content/Context;J)V"),
            &[
                jni::objects::JValue::Object(&object),
                jni::objects::JValue::Long(REQUEST.get().unwrap().generation as i64),
            ],
        )?;
        Ok(())
    })
    .map_err(|e| e.to_string())
}

#[cfg(not(feature = "showcase"))]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_NativeMetaInputDiagnostic_nativeHeldReviewEnabled(
    _env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
) -> u8 {
    enabled() as u8
}
#[cfg(not(feature = "showcase"))]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_NativeMetaInputDiagnostic_nativeHeldReviewGeneration(
    _env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
) -> jni::sys::jlong {
    if !enabled() {
        return 0;
    }
    POLICY
        .lock()
        .ok()
        .and_then(|p| p.as_ref().map(|p| p.generation as i64))
        .unwrap_or(0)
}
#[cfg(not(feature = "showcase"))]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_NativeMetaInputDiagnostic_nativeRequestHeldReview(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    input: jni::objects::JString,
) -> u8 {
    match env.with_env(|env| input.try_to_string(env)).into_outcome() {
        jni::Outcome::Ok(s) => request(&s) as u8,
        _ => 0,
    }
}
#[cfg(not(feature = "showcase"))]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_NativeMetaInputDiagnostic_nativeHeldReviewStatus(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
) -> jni::sys::jstring {
    match env
        .with_env(|env| env.new_string(status()).map(|s| s.into_raw()))
        .into_outcome()
    {
        jni::Outcome::Ok(s) => s,
        _ => std::ptr::null_mut(),
    }
}
