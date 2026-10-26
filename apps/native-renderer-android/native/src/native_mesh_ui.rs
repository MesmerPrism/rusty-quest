//! Native mesh-panel state, bounded ray-to-widget input and Android Presentation bridge.
use std::sync::{Mutex, OnceLock};

/// A next-image acquisition owns its own duplicated fence. Never mix pairs while draining.
#[cfg(any(test, target_os = "android"))]
mod acquire_fence {
    pub(super) struct Pending<I> {
        pub image: I,
        pub fence: i32,
    }
    /// Bounded nonblocking queue drain; transfer each discarded pair's own fence exactly once.
    pub(super) fn latest<I>(
        limit: usize,
        mut acquire: impl FnMut() -> Option<Pending<I>>,
        mut release: impl FnMut(Pending<I>),
    ) -> Option<Pending<I>> {
        let mut latest = None;
        for _ in 0..limit {
            let Some(next) = acquire() else {
                break;
            };
            if let Some(old) = latest.replace(next) {
                release(old);
            }
        }
        latest
    }
    #[derive(PartialEq, Debug)]
    pub(super) enum Probe {
        Pending,
        Ready,
        Quarantine,
    }
    pub(super) fn probe(fence: i32, result: i32, events: i16) -> Probe {
        if fence < 0 {
            Probe::Ready
        } else if result <= 0 {
            Probe::Pending
        } else if events & (8 | 16 | 32) != 0 {
            Probe::Quarantine
        } else if events & 1 != 0 {
            Probe::Ready
        } else {
            Probe::Pending
        }
    }
    #[cfg(test)]
    mod tests {
        use super::*;
        use std::collections::BTreeSet;
        #[test]
        fn drain_releases_own_fds_once_and_preserves_latest() {
            let mut queue = (1..=3).map(|i| Pending {
                image: i,
                fence: 210 + i,
            });
            let mut released = Vec::new();
            let latest = latest(8, || queue.next(), |p| released.push((p.image, p.fence))).unwrap();
            assert_eq!(released, vec![(1, 211), (2, 212)]);
            assert_eq!((latest.image, latest.fence), (3, 213));
            let mut closed = BTreeSet::new();
            for (_, fd) in released {
                assert!(closed.insert(fd));
            }
            assert_eq!(probe(latest.fence, 0, 0), Probe::Pending);
            assert!(!closed.contains(&latest.fence));
            assert_eq!(probe(latest.fence, 1, 1), Probe::Ready);
            assert!(closed.insert(latest.fence));
            assert_eq!(closed.len(), 3);
        }
        #[test]
        fn drain_is_bounded_even_when_producer_continues() {
            let mut reads = 0;
            let mut releases = 0;
            let p = latest(
                8,
                || {
                    reads += 1;
                    Some(Pending {
                        image: reads,
                        fence: reads + 100,
                    })
                },
                |_| releases += 1,
            )
            .unwrap();
            assert_eq!((reads, releases, p.image, p.fence), (8, 7, 8, 108));
        }
        #[test]
        fn invalid_or_error_fence_never_becomes_ready_or_releaseable() {
            for events in [8, 16, 32, 33] {
                assert_eq!(probe(213, 1, events), Probe::Quarantine);
            }
            assert_eq!(probe(213, -1, 0), Probe::Pending);
            assert_eq!(probe(-1, 0, 0), Probe::Ready);
        }
    }
}

pub(crate) const WIDTH: u32 = 1024;
pub(crate) const HEIGHT: u32 = 768;
#[derive(Clone, Copy, Debug)]
pub(crate) struct PanelPose {
    pub(crate) center: [f32; 3],
    pub(crate) right: [f32; 3],
    pub(crate) up: [f32; 3],
    pub(crate) width: f32,
    pub(crate) height: f32,
}
impl Default for PanelPose {
    fn default() -> Self {
        Self {
            center: [0.0, 0.0, -1.4],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            width: 0.96,
            height: 0.72,
        }
    }
}
#[derive(Clone, Copy, Debug)]
pub(crate) struct ControlSnapshot {
    pub(crate) generation: u64,
    pub(crate) revision: u64,
    pub(crate) renderer_observed_revision: u64,
    pub(crate) animation: bool,
    pub(crate) replay: bool,
    pub(crate) diagnostics: bool,
    pub(crate) grafts: bool,
    pub(crate) camera: bool,
    pub(crate) particles: bool,
    pub(crate) hands: bool,
    pub(crate) effect_index: u32,
    pub(crate) effect_selected: bool,
    pub(crate) graft_scale: f32,
    pub(crate) particle_scale: f32,
    pub(crate) pose: PanelPose,
    pub(crate) pose_initialized: bool,
    pub(crate) initial_pose: PanelPose,
    pub(crate) pose_distance: f32,
    pub(crate) panel_visible: bool,
    pub(crate) visibility_revision: u64,
    pub(crate) effective_panel_visible: bool,
    pub(crate) effective_visibility_revision: u64,
}
impl Default for ControlSnapshot {
    fn default() -> Self {
        Self {
            generation: 0,
            revision: 0,
            renderer_observed_revision: 0,
            animation: true,
            replay: false,
            diagnostics: true,
            grafts: true,
            camera: true,
            particles: true,
            hands: true,
            effect_index: 0,
            effect_selected: false,
            graft_scale: 1.0,
            particle_scale: 0.5,
            pose: PanelPose::default(),
            pose_initialized: false,
            initial_pose: PanelPose::default(),
            pose_distance: 1.4,
            panel_visible: true,
            visibility_revision: 0,
            effective_panel_visible: false,
            effective_visibility_revision: 0,
        }
    }
}
static CONTROLS: OnceLock<Mutex<ControlSnapshot>> = OnceLock::new();
fn controls() -> &'static Mutex<ControlSnapshot> {
    CONTROLS.get_or_init(|| Mutex::new(ControlSnapshot::default()))
}
pub(crate) fn snapshot() -> ControlSnapshot {
    *controls().lock().unwrap_or_else(|p| p.into_inner())
}
fn set_visibility(state: &mut ControlSnapshot, generation: u64, visible: bool) -> Option<u64> {
    if generation == 0 || state.generation != generation || state.revision >= i64::MAX as u64 {
        return None;
    }
    state.revision += 1;
    state.panel_visible = visible;
    state.visibility_revision = state.revision;
    Some(state.visibility_revision)
}
pub(crate) fn request_visibility(generation: u64, visible: bool) -> Option<u64> {
    set_visibility(
        &mut controls().lock().unwrap_or_else(|p| p.into_inner()),
        generation,
        visible,
    )
}
fn observe_visibility(state: &mut ControlSnapshot, frame: ControlSnapshot) -> bool {
    if frame.generation == 0
        || frame.generation != state.generation
        || frame.visibility_revision > state.visibility_revision
        || frame.visibility_revision < state.effective_visibility_revision
    {
        return false;
    }
    let changed = state.effective_visibility_revision != frame.visibility_revision
        || state.effective_panel_visible != frame.panel_visible;
    state.effective_panel_visible = frame.panel_visible;
    state.effective_visibility_revision = frame.visibility_revision;
    changed
}
/// Called only after the frame carrying this frozen visibility state was submitted.
pub(crate) fn renderer_observed_visibility(frame: ControlSnapshot) -> bool {
    observe_visibility(
        &mut controls().lock().unwrap_or_else(|p| p.into_inner()),
        frame,
    )
}
fn visibility_status(state: ControlSnapshot, generation: u64, revision: u64) -> &'static str {
    if generation == 0 || generation != state.generation {
        "unavailable"
    } else if revision != state.visibility_revision {
        "stale"
    } else if state.effective_visibility_revision == revision
        && state.effective_panel_visible == state.panel_visible
    {
        "applied"
    } else {
        "pending"
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_NativeMeshUiProducer_nativeSetPanelVisible(
    _env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    generation: i64,
    visible: jni::sys::jboolean,
) -> i64 {
    if generation <= 0 {
        return 0;
    }
    request_visibility(generation as u64, visible).unwrap_or(0) as i64
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_NativeMeshUiProducer_nativeReadPanelVisibility(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    generation: i64,
    revision: i64,
) -> jni::sys::jstring {
    use jni::Outcome;
    let state = snapshot();
    let requested_revision = if revision == 0 {
        state.visibility_revision as i64
    } else {
        revision
    };
    let status = if generation <= 0 || revision < 0 {
        "unavailable"
    } else {
        visibility_status(state, generation as u64, requested_revision as u64)
    };
    let body = serde_json::json!({
        "schema":"rusty.quest.native_mesh_ui.panel_visibility.v1", "generation":generation,
        "request_revision":requested_revision, "requested_visible":state.panel_visible,
        "effective_revision":state.effective_visibility_revision,
        "effective_visible":state.effective_panel_visible, "status":status,
        "evidence":"native-submitted-frame", "visible_confirmation":false,
    })
    .to_string();
    match env
        .with_env(|env| env.new_string(body).map(|s| s.into_raw()))
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
}
/// Calibrate once from a valid central eye in the renderer's actual reference space.
/// No floor-height assumption or continuing head lock is introduced.
pub(crate) fn initialize_pose(generation: u64, position: [f32; 3], orientation: [f32; 4]) -> bool {
    let mut state = controls().lock().unwrap_or_else(|p| p.into_inner());
    calibrate(&mut state, generation, position, orientation)
}
fn calibrate(
    state: &mut ControlSnapshot,
    generation: u64,
    position: [f32; 3],
    orientation: [f32; 4],
) -> bool {
    if generation == 0 || state.generation != generation || state.pose_initialized {
        return false;
    }
    let Some(pose) = head_relative_pose(position, orientation) else {
        return false;
    };
    state.pose = pose;
    state.initial_pose = pose;
    state.pose_initialized = true;
    state.pose_distance = 1.4;
    true
}
fn head_relative_pose(position: [f32; 3], orientation: [f32; 4]) -> Option<PanelPose> {
    if position
        .iter()
        .chain(orientation.iter())
        .any(|x| !x.is_finite())
    {
        return None;
    }
    let norm = orientation.iter().map(|x| x * x).sum::<f32>().sqrt();
    if !norm.is_finite() || norm < 0.001 {
        return None;
    }
    let q = orientation.map(|x| x / norm);
    let rotate = |v: [f32; 3]| {
        let xyz = [q[0], q[1], q[2]];
        let first = cross(xyz, v);
        let second = cross(xyz, first);
        std::array::from_fn(|i| v[i] + 2.0 * (q[3] * first[i] + second[i]))
    };
    let forward: [f32; 3] = rotate([0.0, 0.0, -1.0]);
    Some(PanelPose {
        center: std::array::from_fn(|i| position[i] + forward[i] * 1.4),
        right: rotate([1.0, 0.0, 0.0]),
        up: rotate([0.0, 1.0, 0.0]),
        ..PanelPose::default()
    })
}
pub(crate) fn enabled_for_mode(mode: &str) -> bool {
    option_env!("RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ENABLED") == Some("true")
        && mode == "native-worldspace-integration"
}
/// Records host frame application only; does not claim visible GPU adoption.
pub(crate) fn renderer_observed(generation: u64, revision: u64) -> bool {
    let mut s = controls().lock().unwrap_or_else(|p| p.into_inner());
    if generation == 0 || s.generation != generation || revision > s.revision {
        return false;
    }
    s.renderer_observed_revision = s.renderer_observed_revision.max(revision);
    true
}
pub(crate) fn activate(generation: u64) {
    #[cfg(native_meta_xr_operator)]
    crate::native_input_geometry::reset();
    *controls().lock().unwrap_or_else(|p| p.into_inner()) = ControlSnapshot {
        generation,
        ..ControlSnapshot::default()
    };
}
pub(crate) fn deactivate(generation: u64) {
    let mut s = controls().lock().unwrap_or_else(|p| p.into_inner());
    if s.generation == generation {
        s.generation = 0;
        #[cfg(native_meta_xr_operator)]
        crate::native_input_geometry::reset();
    }
}
fn apply(s: &mut ControlSnapshot, generation: u64, code: i32, value: f32) -> bool {
    if generation == 0
        || generation != s.generation
        || !value.is_finite()
        || !(0.0..=1.0).contains(&value)
    {
        return false;
    }
    match code {
        1 => s.animation = !s.animation,
        2 => s.replay = !s.replay,
        3 => s.diagnostics = !s.diagnostics,
        4 => s.grafts = !s.grafts,
        5 => s.particle_scale = value,
        6 => {
            if !s.pose_initialized {
                return false;
            }
            let next = if s.pose_distance > 1.6 { 1.2 } else { 1.9 };
            let normal = cross(s.pose.right, s.pose.up);
            s.pose.center =
                std::array::from_fn(|i| s.pose.center[i] + normal[i] * (s.pose_distance - next));
            s.pose_distance = next;
        }
        7 => {
            s.pose.width = if s.pose.width > 1.0 { 0.96 } else { 1.2 };
            s.pose.height = s.pose.width * 0.75;
        }
        8 => s.camera = !s.camera,
        9 => s.particles = !s.particles,
        10 => s.hands = !s.hands,
        11 => {
            s.effect_index = if s.effect_selected {
                (s.effect_index + 1) % 7
            } else {
                0
            };
            s.effect_selected = true;
        }
        12 => s.graft_scale = 0.10 + 1.90 * value,
        13 => {
            if !s.pose_initialized {
                return false;
            }
            s.pose = s.initial_pose;
            s.pose_distance = 1.4;
        }
        _ => return false,
    }
    s.revision = s.revision.saturating_add(1);
    true
}
fn dot(a: [f32; 3], b: [f32; 3]) -> f32 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
fn cross(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}
/// Returns unclamped surface pixels for drag capture; rejects behind/parallel/nonfinite rays.
pub(crate) fn ray_pixels(
    pose: PanelPose,
    origin: [f32; 3],
    direction: [f32; 3],
) -> Option<[f32; 2]> {
    if origin
        .iter()
        .chain(direction.iter())
        .chain(pose.center.iter())
        .chain(pose.right.iter())
        .chain(pose.up.iter())
        .any(|x| !x.is_finite())
        || !pose.width.is_finite()
        || !pose.height.is_finite()
        || pose.width <= 0.0
        || pose.height <= 0.0
        || (dot(direction, direction) - 1.0).abs() > 0.01
        || (dot(pose.right, pose.right) - 1.0).abs() > 0.01
        || (dot(pose.up, pose.up) - 1.0).abs() > 0.01
        || dot(pose.right, pose.up).abs() > 0.01
    {
        return None;
    }
    let normal = cross(pose.right, pose.up);
    let denominator = dot(direction, normal);
    if denominator >= -1.0e-5 {
        return None;
    }
    let to_center = std::array::from_fn(|i| pose.center[i] - origin[i]);
    let t = dot(to_center, normal) / denominator;
    if !(0.0..=5.0).contains(&t) {
        return None;
    }
    let offset = std::array::from_fn(|i| origin[i] + direction[i] * t - pose.center[i]);
    Some([
        (dot(offset, pose.right) / pose.width + 0.5) * WIDTH as f32,
        (0.5 - dot(offset, pose.up) / pose.height) * HEIGHT as f32,
    ])
}
#[derive(Default)]
pub(crate) struct PointerCapture {
    pressed: bool,
    captured: bool,
    last: [f32; 2],
}
impl PointerCapture {
    pub(crate) fn captured(&self) -> bool {
        self.captured
    }
    /// Codes: 0 down,1 move,2 up,3 cancel. One authoritative pointer owner only.
    pub(crate) fn update(
        &mut self,
        valid: bool,
        pressed: bool,
        pixels: Option<[f32; 2]>,
    ) -> Option<(i32, [f32; 2])> {
        if !valid {
            let had = self.captured;
            self.captured = false;
            self.pressed = pressed;
            return had.then_some((3, self.last));
        }
        let edge = pressed && !self.pressed;
        self.pressed = pressed;
        if self.captured {
            if let Some(p) = pixels {
                self.last = [p[0].clamp(0.0, 1023.0), p[1].clamp(0.0, 767.0)];
            }
            if !pressed {
                self.captured = false;
                return Some((2, self.last));
            }
            return Some((1, self.last));
        }
        if edge {
            if let Some(p) = pixels {
                if (0.0..WIDTH as f32).contains(&p[0]) && (0.0..HEIGHT as f32).contains(&p[1]) {
                    self.captured = true;
                    self.last = p;
                    return Some((0, p));
                }
            }
        }
        None
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_NativeMeshUiProducer_nativeControl(
    _env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    generation: i64,
    code: i32,
    value: f32,
) -> jni::sys::jboolean {
    if code == 14 {
        return crate::native_development_feedback::enqueue_ui_compute(generation as u64).into();
    }
    let accepted = apply(
        &mut controls().lock().unwrap_or_else(|p| p.into_inner()),
        generation as u64,
        code,
        value,
    );
    if accepted {
        let s = snapshot();
        crate::marker(
            "native-mesh-ui",
            format!(
                "event=control-state-adopted generation={} revision={} code={} value={value:.3}",
                s.generation, s.revision, code
            ),
        );
    }
    accepted as jni::sys::jboolean
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_NativeMeshUiProducer_nativeInputSelected(
    _env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
) -> jni::sys::jboolean {
    cfg!(native_meta_xr_operator).into()
}

#[cfg(all(target_os = "android", native_meta_xr_operator))]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_NativeMeshUiProducer_nativePublishInputGeometry(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    generation: i64,
    body: jni::objects::JString,
) -> jni::sys::jboolean {
    use jni::Outcome;
    let current = snapshot().generation;
    if generation <= 0 || current != generation as u64 {
        return false.into();
    }
    let result = env
        .with_env(|env| {
            let text: String = env.get_string(&body)?.into();
            Ok::<bool, jni::errors::Error>(
                crate::native_input_geometry::update(current, &text).is_ok(),
            )
        })
        .into_outcome();
    matches!(result, Outcome::Ok(true)).into()
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_NativeMeshUiProducer_nativeFeedbackSelected(
    _env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
) -> jni::sys::jboolean {
    crate::native_development_feedback::selected().into()
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_NativeMeshUiProducer_nativeFeedbackStatus(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    generation: i64,
) -> jni::sys::jstring {
    use jni::Outcome;
    let body = crate::native_development_feedback::ui_status(generation as u64);
    match env
        .with_env(|env| env.new_string(body).map(|s| s.into_raw()))
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[cfg(target_os = "android")]
pub(crate) mod android {
    use super::*;
    use crate::acamera_sys as media;
    use crate::android_hardware_buffer::AndroidHardwareBufferHandle;
    use jni::{
        jni_sig, jni_str,
        objects::{JClass, JClassLoader, JObject, JValue},
        JavaVM,
    };
    use std::ptr;
    #[link(name = "android")]
    extern "C" {
        fn ANativeWindow_toSurface(
            env: *mut jni::sys::JNIEnv,
            window: *mut ndk_sys::ANativeWindow,
        ) -> jni::sys::jobject;
    }
    pub(crate) struct ImageLease {
        pub(crate) image: *mut media::AImage,
        pub(crate) buffer: AndroidHardwareBufferHandle,
    }
    #[link(name = "mediandk")]
    extern "C" {
        fn AImageReader_acquireNextImageAsync(
            reader: *mut media::AImageReader,
            image: *mut *mut media::AImage,
            acquire_fence_fd: *mut i32,
        ) -> i32;
    }
    #[repr(C)]
    struct PollFd {
        fd: i32,
        events: i16,
        revents: i16,
    }
    extern "C" {
        fn poll(fds: *mut PollFd, count: usize, timeout: i32) -> i32;
        fn close(fd: i32) -> i32;
    }
    impl Drop for ImageLease {
        fn drop(&mut self) {
            unsafe {
                media::AImage_delete(self.image);
            }
        }
    }
    pub(crate) struct Producer {
        reader: *mut media::AImageReader,
        pub(crate) generation: u64,
        pending: Option<acquire_fence::Pending<*mut media::AImage>>,
        acquire_quarantined: bool,
    }
    impl Producer {
        pub(crate) unsafe fn start(
            app: &android_activity::AndroidApp,
            generation: u64,
        ) -> Result<Self, String> {
            let mut reader = ptr::null_mut();
            // RGBA8, GPU_SAMPLED_IMAGE|GPU_COLOR_OUTPUT. Retained AImages prevent producer recycling.
            if media::AImageReader_newWithUsage(
                WIDTH as i32,
                HEIGHT as i32,
                1,
                (1 << 8) | (1 << 9),
                8,
                &mut reader,
            ) != 0
                || reader.is_null()
            {
                return Err("create mesh UI RGBA reader".into());
            }
            let mut window = ptr::null_mut();
            if media::AImageReader_getWindow(reader, &mut window) != 0 {
                media::AImageReader_delete(reader);
                return Err("mesh UI reader window".into());
            }
            activate(generation);
            let result = with_class(app, |env, class, activity| {
                let raw = ANativeWindow_toSurface(env.get_raw(), window);
                let surface = env.as_cast_raw::<JObject>(&raw)?;
                env.call_static_method(
                    class,
                    jni_str!("start"),
                    jni_sig!("(Landroid/app/Activity;Landroid/view/Surface;IIJ)V"),
                    &[
                        JValue::Object(activity),
                        JValue::Object(&surface),
                        JValue::Int(WIDTH as i32),
                        JValue::Int(HEIGHT as i32),
                        JValue::Long(generation as i64),
                    ],
                )?;
                Ok(())
            });
            if let Err(error) = result {
                // Exceptional return still joins any possibly queued Java start.
                let owner = Self {
                    reader,
                    generation,
                    pending: None,
                    acquire_quarantined: false,
                };
                let _ = owner.stop(app);
                return Err(error);
            }
            Ok(Self {
                reader,
                generation,
                pending: None,
                acquire_quarantined: false,
            })
        }
        pub(crate) unsafe fn acquire(&mut self) -> Option<ImageLease> {
            if self.acquire_quarantined {
                return None;
            }
            if self.pending.is_none() {
                // Avoid acquireLatestImageAsync: Android 14/15 implementations overwrite the
                // previous output FD before dropping that image, returning a consumed next FD.
                self.pending = acquire_fence::latest(
                    8,
                    || {
                        let mut image = ptr::null_mut();
                        let mut fence = -1;
                        if AImageReader_acquireNextImageAsync(self.reader, &mut image, &mut fence)
                            != 0
                            || image.is_null()
                        {
                            None
                        } else {
                            Some(acquire_fence::Pending { image, fence })
                        }
                    },
                    |old| media::AImage_deleteAsync(old.image, old.fence),
                );
            }
            let pending = self.pending.as_ref()?;
            let mut fd = PollFd {
                fd: pending.fence,
                events: 1,
                revents: 0,
            };
            // Probe without blocking XR. Keep image and acquire fence paired until ready.
            let result = if pending.fence >= 0 {
                poll(&mut fd, 1, 0)
            } else {
                0
            };
            match acquire_fence::probe(pending.fence, result, fd.revents) {
                acquire_fence::Probe::Pending => return None,
                acquire_fence::Probe::Quarantine => {
                    // A bad FD is no longer ours to close/transfer: it could have been reused.
                    // Keep the reader/image alive and stop acquisition; never recycle early.
                    self.acquire_quarantined = true;
                    crate::marker(
                        "native-mesh-ui",
                        "event=acquire-fence-quarantined readerRetained=true".to_string(),
                    );
                    return None;
                }
                acquire_fence::Probe::Ready => {}
            }
            let pending = self.pending.take()?;
            if pending.fence >= 0 {
                close(pending.fence);
            }
            let image = pending.image;
            let mut buffer = ptr::null_mut();
            if media::AImage_getHardwareBuffer(image, &mut buffer) != 0 {
                media::AImage_delete(image);
                return None;
            }
            match AndroidHardwareBufferHandle::acquire(buffer) {
                Ok(buffer) => Some(ImageLease { image, buffer }),
                Err(_) => {
                    media::AImage_delete(image);
                    None
                }
            }
        }
        pub(crate) fn pointer(
            &self,
            app: &android_activity::AndroidApp,
            action: i32,
            p: [f32; 2],
        ) -> Result<(), String> {
            with_class(app, |env, class, _| {
                env.call_static_method(
                    class,
                    jni_str!("pointer"),
                    jni_sig!("(JIFFJ)V"),
                    &[
                        JValue::Long(self.generation as i64),
                        JValue::Int(action),
                        JValue::Float(p[0]),
                        JValue::Float(p[1]),
                        JValue::Long(0),
                    ],
                )?;
                Ok(())
            })
        }
        /// Caller must first retire all Vk fences/imports; stop Java before reader destruction.
        pub(crate) unsafe fn stop(
            mut self,
            app: &android_activity::AndroidApp,
        ) -> Result<(), String> {
            if !self.acquire_quarantined {
                if let Some(pending) = self.pending.take() {
                    media::AImage_deleteAsync(pending.image, pending.fence);
                }
            }
            deactivate(self.generation);
            let result = wait_for_writer_retirement(app, self.generation);
            // If Android UI shutdown has not acknowledged, keep the reader alive rather than
            // destroying a producer resource still referenced by a live VirtualDisplay.
            if result.is_ok() && !self.acquire_quarantined {
                media::AImageReader_delete(self.reader);
            }
            result
        }
    }
    impl Producer {
        /// Quiesce Java without recycling images whose Vulkan release failed.
        pub(crate) unsafe fn quiesce(mut self, app: &android_activity::AndroidApp) {
            deactivate(self.generation);
            if !self.acquire_quarantined {
                if let Some(pending) = self.pending.take() {
                    media::AImage_deleteAsync(pending.image, pending.fence);
                }
            }
            let _ = wait_for_writer_retirement(app, self.generation);
        }
    }
    fn wait_for_writer_retirement(
        app: &android_activity::AndroidApp,
        generation: u64,
    ) -> Result<(), String> {
        let mut admitted = false;
        with_class(app, |env, class, _| {
            admitted = env
                .call_static_method(
                    class,
                    jni_str!("requestStop"),
                    jni_sig!("(J)Z"),
                    &[JValue::Long(generation as i64)],
                )?
                .z()?;
            Ok(())
        })?;
        if !admitted {
            return Err("mesh UI stop request rejected; reader retained".into());
        }
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        let mut running = true;
        loop {
            // NativeActivity's UI-thread window/lifecycle callbacks wait for this thread's
            // event acknowledgment. A synchronous Java latch wait here prevents queued UI
            // writer-close execution. Pump only the existing Android event owner, not XR.
            crate::android_events::pump_activity_events(
                app,
                std::time::Duration::from_millis(0),
                &mut running,
            );
            let mut state = 0;
            with_class(app, |env, class, _| {
                state = env
                    .call_static_method(
                        class,
                        jni_str!("stopStatus"),
                        jni_sig!("(J)I"),
                        &[JValue::Long(generation as i64)],
                    )?
                    .i()?;
                Ok(())
            })?;
            if state == 1 {
                crate::marker(
                    "native-mesh-ui",
                    format!(
                        "event=producer-stop-ack-observed generation={} writerClosed=true",
                        generation
                    ),
                );
                return Ok(());
            }
            if state < 0 {
                return Err("mesh UI writer close failed; reader retained".into());
            }
            if !running {
                return Err(
                    "Activity destroyed before mesh UI writer acknowledgment; reader retained"
                        .into(),
                );
            }
            if std::time::Instant::now() >= deadline {
                return Err("mesh UI producer stop not acknowledged within bounded event-pumped wait; reader retained".into());
            }
            std::thread::sleep(std::time::Duration::from_millis(1));
        }
    }
    fn with_class<F>(app: &android_activity::AndroidApp, call: F) -> Result<(), String>
    where
        F: for<'a, 'b> FnOnce(
            &mut jni::Env<'a>,
            JClass<'a>,
            &JObject<'b>,
        ) -> jni::errors::Result<()>,
    {
        let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
        let raw = app.activity_as_ptr() as jni::sys::jobject;
        vm.attach_current_thread(|env| {
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
            let name = env.new_string(
                "io.github.mesmerprism.rustyquest.native_renderer.NativeMeshUiProducer",
            )?;
            let class = JClass::for_name_with_loader(env, name, true, loader)?;
            call(env, class, &activity)
        })
        .map_err(|e| format!("mesh UI Java bridge: {e}"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn visibility_request_needs_its_submitted_frame_and_preserves_scene_controls() {
        let mut state = ControlSnapshot {
            generation: 7,
            ..ControlSnapshot::default()
        };
        assert_eq!(visibility_status(state, 7, 0), "pending");
        let revision = set_visibility(&mut state, 7, false).unwrap();
        assert_eq!(visibility_status(state, 7, revision), "pending");
        assert!(state.particles && state.animation && state.hands);
        let submitted = state;
        assert!(observe_visibility(&mut state, submitted));
        assert_eq!(visibility_status(state, 7, revision), "applied");
        assert!(!state.effective_panel_visible);
        assert!(!observe_visibility(&mut state, submitted));
    }
    #[test]
    fn later_show_is_not_acknowledged_by_an_older_hidden_frame() {
        let mut state = ControlSnapshot {
            generation: 7,
            ..ControlSnapshot::default()
        };
        let hidden_revision = set_visibility(&mut state, 7, false).unwrap();
        let hidden_frame = state;
        let shown_revision = set_visibility(&mut state, 7, true).unwrap();
        assert!(observe_visibility(&mut state, hidden_frame));
        assert_eq!(visibility_status(state, 7, hidden_revision), "stale");
        assert_eq!(visibility_status(state, 7, shown_revision), "pending");
        let shown_frame = state;
        assert!(observe_visibility(&mut state, shown_frame));
        assert_eq!(visibility_status(state, 7, shown_revision), "applied");
        assert!(state.effective_panel_visible);
        assert!(!observe_visibility(&mut state, hidden_frame));
        assert!(state.effective_panel_visible);
    }
    #[test]
    fn visibility_retired_generation_and_future_revision_fail_closed() {
        let mut state = ControlSnapshot {
            generation: 8,
            ..ControlSnapshot::default()
        };
        assert!(set_visibility(&mut state, 7, false).is_none());
        assert!(set_visibility(&mut state, 0, false).is_none());
        assert_eq!(visibility_status(state, 7, 0), "unavailable");
        let retired = ControlSnapshot {
            generation: 7,
            ..state
        };
        let future = ControlSnapshot {
            visibility_revision: 10,
            ..state
        };
        assert!(!observe_visibility(&mut state, retired));
        assert!(!observe_visibility(&mut state, future));
        state.revision = i64::MAX as u64;
        assert!(set_visibility(&mut state, 8, false).is_none());
        assert!(state.panel_visible);
    }
    #[test]
    fn ray_mapping_rejects_backface_and_behind() {
        let p = PanelPose {
            center: [0.0, 1.35, -1.4],
            ..PanelPose::default()
        };
        assert_eq!(
            ray_pixels(p, [0.0, 1.35, 0.0], [0.0, 0.0, -1.0]),
            Some([512.0, 384.0])
        );
        assert!(ray_pixels(p, [0.0, 1.35, -2.0], [0.0, 0.0, -1.0]).is_none());
        assert!(ray_pixels(p, [0.0, 1.35, 0.0], [f32::NAN, 0.0, -1.0]).is_none());
    }
    #[test]
    fn drag_stays_captured_and_tracking_loss_cancels() {
        let mut c = PointerCapture::default();
        assert_eq!(
            c.update(true, true, Some([40.0, 20.0])),
            Some((0, [40.0, 20.0]))
        );
        assert_eq!(
            c.update(true, true, Some([2000.0, -5.0])),
            Some((1, [1023.0, 0.0]))
        );
        assert_eq!(c.update(false, true, None), Some((3, [1023.0, 0.0])));
        assert_eq!(c.update(true, true, Some([40.0, 20.0])), None);
    }
    #[test]
    fn stale_and_unknown_controls_reject() {
        let mut s = ControlSnapshot {
            generation: 7,
            ..Default::default()
        };
        assert!(!apply(&mut s, 6, 1, 1.0));
        assert!(!apply(&mut s, 7, 99, 1.0));
        assert!(!apply(&mut s, 7, 5, f32::NAN));
        assert!(apply(&mut s, 7, 5, 0.8));
        assert_eq!(s.revision, 1);
        assert_eq!(s.particle_scale, 0.8);
    }
    #[test]
    fn cancelled_frame_cannot_complete_the_previous_widget_press() {
        let mut capture = PointerCapture::default();
        let pixels = Some([40.0, 20.0]);
        assert_eq!(capture.update(true, true, pixels), Some((0, [40.0, 20.0])));
        // The renderer uses this cancellation when head views lose validity.
        assert_eq!(capture.update(false, false, None), Some((3, [40.0, 20.0])));
        assert!(!capture.captured());
        // Releasing during the skipped frame must not become an ACTION_UP click
        // on recovery. A subsequent deliberate press/release remains usable.
        assert_eq!(capture.update(true, false, pixels), None);
        assert_eq!(capture.update(true, true, pixels), Some((0, [40.0, 20.0])));
        assert_eq!(capture.update(true, false, pixels), Some((2, [40.0, 20.0])));
    }
    #[test]
    fn effect_override_is_explicit_and_has_closed_seven_slot_cycle() {
        let mut state = ControlSnapshot {
            generation: 7,
            ..Default::default()
        };
        assert!(!state.effect_selected);
        assert!(apply(&mut state, 7, 11, 1.0));
        assert!(state.effect_selected);
        assert_eq!(state.effect_index, 0);
        for expected in [1, 2, 3, 4, 5, 6, 0] {
            assert!(apply(&mut state, 7, 11, 1.0));
            assert_eq!(state.effect_index, expected);
        }
    }
    #[test]
    fn pose_calibration_matches_eye_space_and_remains_world_locked() {
        let half = std::f32::consts::FRAC_1_SQRT_2;
        let mut state = ControlSnapshot {
            generation: 7,
            ..Default::default()
        };
        assert!(calibrate(
            &mut state,
            7,
            [2.0, 1.65, 3.0],
            [0.0, half, 0.0, half]
        ));
        assert!((state.pose.center[0] - 0.6).abs() < 0.00001);
        assert!((state.pose.center[1] - 1.65).abs() < 0.00001);
        assert!((state.pose.center[2] - 3.0).abs() < 0.00001);
        assert!((state.pose.right[2] + 1.0).abs() < 0.00001);
        assert_eq!(state.pose.up, [0.0, 1.0, 0.0]);
        let initial = state.pose;
        assert!(!calibrate(
            &mut state,
            7,
            [20.0, 10.0, 15.0],
            [0.0, 0.0, 0.0, 1.0]
        ));
        assert_eq!(state.pose.center, initial.center);
        assert!(apply(&mut state, 7, 6, 1.0));
        assert!((state.pose.center[0] - 0.1).abs() < 0.00001);
        assert_eq!(state.pose.center[1], initial.center[1]);
        assert_eq!(state.pose.center[2], initial.center[2]);
        assert!(apply(&mut state, 7, 6, 1.0));
        assert!((state.pose.center[0] - 0.8).abs() < 0.00001);
        assert!(apply(&mut state, 7, 7, 1.0));
        assert!(apply(&mut state, 7, 13, 1.0));
        assert_eq!(state.pose.center, initial.center);
        assert_eq!(state.pose.width, initial.width);
        assert_eq!(state.pose_distance, 1.4);
    }
    #[test]
    fn local_origin_and_tilt_are_valid_but_invalid_orientation_waits() {
        let mut state = ControlSnapshot {
            generation: 7,
            ..Default::default()
        };
        assert!(!calibrate(&mut state, 7, [0.0; 3], [0.0; 4]));
        assert!(!state.pose_initialized);
        assert!(calibrate(&mut state, 7, [0.0; 3], [0.0, 0.0, 0.0, 1.0]));
        assert_eq!(state.pose.center, [0.0, 0.0, -1.4]);
        let angle = std::f32::consts::FRAC_PI_8;
        let pose =
            head_relative_pose([0.0, 1.65, 0.0], [angle.sin(), 0.0, 0.0, angle.cos()]).unwrap();
        let half = std::f32::consts::FRAC_1_SQRT_2;
        assert!((pose.up[1] - half).abs() < 0.00001);
        assert!((pose.up[2] - half).abs() < 0.00001);
        assert!((pose.center[1] - (1.65 + 1.4 * half)).abs() < 0.00001);
    }
}
