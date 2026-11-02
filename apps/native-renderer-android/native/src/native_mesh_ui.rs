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

pub(crate) const WIDTH: u32 = super::native_panel_resolution::LOGICAL_WIDTH;
pub(crate) const HEIGHT: u32 = super::native_panel_resolution::LOGICAL_HEIGHT;
pub(crate) use crate::native_panel_model::PanelPose;
#[derive(Clone, Copy, Debug)]
struct SubmittedGeometry {
    pose: PanelPose,
    pose_revision: u64,
    frame: u64,
    reference_space: &'static str,
    visible: bool,
    attachment: &'static str,
    tracking_valid: bool,
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
    pub(crate) pose_revision: u64,
    submitted_geometry: Option<SubmittedGeometry>,
    pub(crate) initial_pose: PanelPose,
    pub(crate) pose_distance: f32,
    pub(crate) panel_visible: bool,
    pub(crate) attachment: &'static str,
    pub(crate) tracking_valid: bool,
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
            pose_revision: 0,
            submitted_geometry: None,
            initial_pose: PanelPose::default(),
            pose_distance: 1.4,
            panel_visible: true,
            attachment: "WORLD",
            tracking_valid: true,
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

fn observe_geometry(
    state: &mut ControlSnapshot,
    frame: ControlSnapshot,
    submitted_frame: u64,
    reference_space: &'static str,
) -> bool {
    if frame.generation == 0
        || frame.generation != state.generation
        || !frame.pose_initialized
        || !matches!(reference_space, "LOCAL" | "STAGE")
        || frame.pose_revision > state.pose_revision
        || state.submitted_geometry.is_some_and(|prior| {
            submitted_frame < prior.frame || frame.pose_revision < prior.pose_revision
        })
    {
        return false;
    }
    state.submitted_geometry = Some(SubmittedGeometry {
        pose: frame.pose,
        pose_revision: frame.pose_revision,
        frame: submitted_frame,
        reference_space,
        visible: frame.panel_visible,
        attachment: frame.attachment,
        tracking_valid: frame.tracking_valid,
    });
    true
}

/// Observation only: caller has submitted this exact frozen frame and completed xrEndFrame.
pub(crate) fn renderer_observed_geometry(
    frame: ControlSnapshot,
    submitted_frame: u64,
    reference_space_type: &'static str,
) -> bool {
    observe_geometry(
        &mut controls().lock().unwrap_or_else(|p| p.into_inner()),
        frame,
        submitted_frame,
        reference_space_type,
    )
}

fn geometry_readback(state: ControlSnapshot, generation: u64) -> serde_json::Value {
    let status = if generation == 0 || generation != state.generation {
        "unavailable"
    } else if !state.pose_initialized {
        "uninitialized"
    } else if state
        .submitted_geometry
        .is_some_and(|seen| seen.pose_revision == state.pose_revision)
    {
        "applied"
    } else {
        "pending"
    };
    let mut value = serde_json::json!({"schema":"rusty.quest.native_mesh_ui.panel_geometry.v1","generation":generation,
        "status":status,"pose_revision":state.pose_revision,"surface_width_pixels":WIDTH,"surface_height_pixels":HEIGHT,
        "evidence":"native-submitted-frame","visible_confirmation":false});
    // No current/requested pose is exposed before its actual submitted frame.
    if status == "applied" {
        let seen = state
            .submitted_geometry
            .expect("applied geometry has submitted observation");
        value["effective_pose_revision"] = serde_json::json!(seen.pose_revision);
        value["submitted_frame"] = serde_json::json!(seen.frame);
        value["reference_space"] = serde_json::json!(seen.reference_space);
        value["frame_panel_visible"] = serde_json::json!(seen.visible);
        value["attachment"] = serde_json::json!(seen.attachment);
        value["tracking_valid"] = serde_json::json!(seen.tracking_valid);
        value["center"] = serde_json::json!(seen.pose.center);
        value["right"] = serde_json::json!(seen.pose.right);
        value["up"] = serde_json::json!(seen.pose.up);
        value["width_meters"] = serde_json::json!(seen.pose.width);
        value["height_meters"] = serde_json::json!(seen.pose.height);
    }
    value
}

#[cfg(target_os = "android")]
#[cfg(any(not(feature = "showcase"), feature = "showcase-vulkan-ui"))]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_NativeMeshUiProducer_nativeReadPanelGeometry(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    generation: i64,
) -> jni::sys::jstring {
    use jni::Outcome;
    let body = geometry_readback(snapshot(), generation.max(0) as u64).to_string();
    match env
        .with_env(|env| env.new_string(body).map(|s| s.into_raw()))
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
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
fn submitted_visibility(state: ControlSnapshot, frame: ControlSnapshot) -> Option<bool> {
    (frame.generation != 0
        && frame.generation == state.generation
        && frame.pose_initialized
        && frame.visibility_revision == state.effective_visibility_revision
        && frame.panel_visible == state.effective_panel_visible)
        .then_some(frame.panel_visible)
}
/// Read only after observing this exact successfully ended frame. A setter or
/// a retired producer cannot supply submitted presentation evidence.
pub(crate) fn renderer_submitted_visibility(frame: ControlSnapshot) -> Option<bool> {
    submitted_visibility(snapshot(), frame)
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
#[cfg(any(not(feature = "showcase"), feature = "showcase-vulkan-ui"))]
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
#[cfg(any(not(feature = "showcase"), feature = "showcase-vulkan-ui"))]
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
        "neutral_controls_enabled":neutral_controls_enabled(),
        "settings_authority":if neutral_controls_enabled() { "neutral-mesh-demo" } else { "original-app-owners" },
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
/// Explicit user recenter using the current valid head pose in the same reference space.
/// Preserve logical/raster density and the user's physical panel size.
pub(crate) fn recenter_pose(generation: u64, position: [f32; 3], orientation: [f32; 4]) -> bool {
    recenter(
        &mut controls().lock().unwrap_or_else(|p| p.into_inner()),
        generation,
        position,
        orientation,
    )
}
fn recenter(
    state: &mut ControlSnapshot,
    generation: u64,
    position: [f32; 3],
    orientation: [f32; 4],
) -> bool {
    if generation == 0
        || state.generation != generation
        || !state.pose_initialized
        || state.revision >= i64::MAX as u64
    {
        return false;
    }
    let Some(mut pose) = head_relative_pose(position, orientation) else {
        return false;
    };
    pose.width = state.pose.width;
    pose.height = state.pose.height;
    state.revision += 1;
    state.pose_revision = state.revision;
    state.pose = pose;
    state.pose_distance = 1.4;
    state.attachment = "WORLD";
    state.tracking_valid = true;
    true
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
    if state.revision >= i64::MAX as u64 {
        return false;
    }
    state.revision += 1;
    state.pose_revision = state.revision;
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
    mesh_selection(
        mode,
        option_env!("RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ENABLED") == Some("true"),
        original_scene_selected(),
    )
}
pub(crate) fn original_scene_selected() -> bool {
    option_env!("RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ORIGINAL_SCENE") == Some("true")
}
fn mesh_selection(mode: &str, packaged: bool, original_scene: bool) -> bool {
    packaged
        && if original_scene {
            mode == "solid-black-private-particles"
        } else {
            mode == "native-worldspace-integration"
        }
}
pub(crate) fn render_configuration_matches(mode: &str, scene_depth: bool) -> bool {
    render_selection(
        mode,
        option_env!("RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ENABLED") == Some("true"),
        original_scene_selected(),
        scene_depth,
    )
}
fn render_selection(mode: &str, packaged: bool, original_scene: bool, scene_depth: bool) -> bool {
    mesh_selection(mode, packaged, original_scene) && scene_depth != original_scene
}
/// The original product panel owns its existing settings handlers. The neutral
/// mesh test panel's defaults never become that product's rendering settings.
pub(crate) fn neutral_controls_enabled() -> bool {
    !cfg!(feature = "showcase-vulkan-ui")
        && option_env!("RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS") != Some("false")
}

pub(crate) fn neutral_frame_controls(frame: Option<ControlSnapshot>) -> Option<ControlSnapshot> {
    frame.filter(|_| neutral_controls_enabled())
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
    *controls().lock().unwrap_or_else(|p| p.into_inner()) = ControlSnapshot {
        generation,
        ..ControlSnapshot::default()
    };
}
pub(crate) fn deactivate(generation: u64) {
    let mut s = controls().lock().unwrap_or_else(|p| p.into_inner());
    if s.generation == generation {
        s.generation = 0;
    }
}
/// Native chrome may mutate presentation geometry only; product settings retain their owner.
pub(crate) fn request_panel_action(generation: u64, action: i32) -> bool {
    panel_action(
        &mut controls().lock().unwrap_or_else(|p| p.into_inner()),
        generation,
        action,
    )
}
/// Absolute presentation width from a native slider; original product settings are untouched.
pub(crate) fn set_panel_width(generation: u64, width: f32) -> bool {
    panel_width(
        &mut controls().lock().unwrap_or_else(|p| p.into_inner()),
        generation,
        width,
    )
}
fn panel_width(s: &mut ControlSnapshot, generation: u64, width: f32) -> bool {
    if generation == 0
        || generation != s.generation
        || !s.pose_initialized
        || !width.is_finite()
        || !(0.48..=1.44).contains(&width)
        || s.revision >= i64::MAX as u64
    {
        return false;
    }
    if (s.pose.width - width).abs() < 0.00001 {
        return true;
    }
    s.pose.width = width;
    s.pose.height = width * 0.75;
    s.revision += 1;
    s.pose_revision = s.revision;
    true
}
/// A current tracked attachment or explicit world recall owns this presentation-only pose.
pub(crate) fn set_attachment_pose(
    generation: u64,
    pose: PanelPose,
    attachment: &'static str,
    tracking_valid: bool,
) -> bool {
    attachment_pose(
        &mut controls().lock().unwrap_or_else(|p| p.into_inner()),
        generation,
        pose,
        attachment,
        tracking_valid,
    )
}
fn attachment_pose(
    s: &mut ControlSnapshot,
    generation: u64,
    pose: PanelPose,
    attachment: &'static str,
    tracking_valid: bool,
) -> bool {
    if generation == 0
        || generation != s.generation
        || !s.pose_initialized
        || !pose.valid()
        || !matches!(attachment, "WORLD" | "WRIST")
        || s.revision >= i64::MAX as u64
    {
        return false;
    }
    if s.pose == pose && s.attachment == attachment && s.tracking_valid == tracking_valid {
        return true;
    }
    s.pose = pose;
    s.attachment = attachment;
    s.tracking_valid = tracking_valid;
    s.revision += 1;
    s.pose_revision = s.revision;
    true
}
fn panel_action(state: &mut ControlSnapshot, generation: u64, action: i32) -> bool {
    matches!(action, 7 | 13) && apply(state, generation, action, 1.0)
}
fn apply(s: &mut ControlSnapshot, generation: u64, code: i32, value: f32) -> bool {
    if generation == 0
        || generation != s.generation
        || s.revision >= i64::MAX as u64
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
            s.attachment = "WORLD";
            s.tracking_valid = true;
        }
        _ => return false,
    }
    s.revision = s.revision.saturating_add(1);
    if matches!(code, 6 | 7 | 13) {
        s.pose_revision = s.revision;
    }
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
    crate::native_panel_model::PanelModel {
        id: 1,
        pose,
        logical: crate::native_panel_model::LogicalBounds {
            min: [0.0, 0.0],
            max: [WIDTH as f32, HEIGHT as f32],
        },
        visible: true,
        enabled: true,
    }
    .map_ray(crate::native_panel_model::Ray { origin, direction })
    .map(|h| h.logical)
}
#[derive(Default)]
pub(crate) struct PointerCapture {
    capture: crate::native_panel_interaction::CaptureState,
    last: [f32; 2],
}
impl PointerCapture {
    /// Only a current fresh edge backed by the source arbiter's valid-open proof may rearm.
    /// Cancel the previous owner first; this method never emits an Android event.
    pub(crate) fn admit_fresh_press(&mut self) {
        if !self.captured() {
            self.capture.update(true, false, None, false);
        }
    }

    pub(crate) fn captured(&self) -> bool {
        self.capture.captured().is_some()
    }
    /// Codes: 0 down,1 move,2 up,3 cancel. One authoritative pointer owner only.
    /// Every invalid sample disarms capture until a valid open sample, including owner changes.
    pub(crate) fn update(
        &mut self,
        valid: bool,
        pressed: bool,
        pixels: Option<[f32; 2]>,
    ) -> Option<(i32, [f32; 2])> {
        let finite = pixels.filter(|p| p.iter().all(|v| v.is_finite()));
        let hit = finite
            .filter(|p| (0.0..WIDTH as f32).contains(&p[0]) && (0.0..HEIGHT as f32).contains(&p[1]))
            .map(|_| crate::native_panel_interaction::ControlId {
                panel: 1,
                control: 0,
            });
        let was_captured = self.captured();
        let outcome = self.capture.update(valid, pressed, hit, false);
        if valid && (was_captured || outcome.pressed.is_some()) {
            if let Some(p) = finite {
                self.last = [
                    p[0].clamp(0.0, WIDTH as f32 - 1.0),
                    p[1].clamp(0.0, HEIGHT as f32 - 1.0),
                ];
            }
        }
        let action = if outcome.cancelled.is_some() {
            3
        } else if outcome.pressed.is_some() {
            0
        } else if outcome.released.is_some() {
            2
        } else if outcome.dragging.is_some() {
            1
        } else {
            return None;
        };
        Some((action, self.last))
    }
}

#[cfg(target_os = "android")]
#[cfg(not(feature = "showcase"))]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_NativeMeshUiProducer_nativeControl(
    _env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    generation: i64,
    code: i32,
    value: f32,
) -> jni::sys::jboolean {
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
pub(crate) mod android {
    use super::super::native_panel_resolution::RasterExtent;
    use super::*;
    use crate::acamera_sys as media;
    use crate::android_hardware_buffer::AndroidHardwareBufferHandle;
    use jni::{
        jni_sig, jni_str,
        objects::{JClass, JClassLoader, JObject, JValue},
        JavaVM,
    };
    use std::{
        cell::Cell,
        ptr,
        rc::{Rc, Weak},
    };
    #[link(name = "android")]
    extern "C" {
        fn ANativeWindow_toSurface(
            env: *mut jni::sys::JNIEnv,
            window: *mut ndk_sys::ANativeWindow,
        ) -> jni::sys::jobject;
    }
    /// A reader cannot disappear while an acquired image or the Java writer still uses it.
    struct ReaderOwner {
        reader: *mut media::AImageReader,
        extent: RasterExtent,
        epoch: u64,
        writer_detached: Cell<bool>,
        quarantined: Cell<bool>,
    }
    impl Drop for ReaderOwner {
        fn drop(&mut self) {
            if self.writer_detached.get() && !self.quarantined.get() {
                unsafe {
                    media::AImageReader_delete(self.reader);
                }
            }
        }
    }
    unsafe fn new_reader(extent: RasterExtent, epoch: u64) -> Result<Rc<ReaderOwner>, String> {
        if !extent.supported() {
            return Err("unsupported mesh UI raster extent".into());
        }
        let mut reader = ptr::null_mut();
        if media::AImageReader_newWithUsage(
            extent.width as i32,
            extent.height as i32,
            1,
            (1 << 8) | (1 << 9),
            8,
            &mut reader,
        ) != 0
            || reader.is_null()
        {
            return Err("create mesh UI RGBA reader".into());
        }
        Ok(Rc::new(ReaderOwner {
            reader,
            extent,
            epoch,
            writer_detached: Cell::new(false),
            quarantined: Cell::new(false),
        }))
    }
    pub(crate) struct ImageLease {
        pub(crate) image: *mut media::AImage,
        pub(crate) buffer: AndroidHardwareBufferHandle,
        pub(crate) raster_extent: RasterExtent,
        pub(crate) raster_epoch: u64,
        // Fields drop after ImageLease::drop calls AImage_delete.
        _reader: Rc<ReaderOwner>,
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
        reader: Rc<ReaderOwner>,
        resize: Option<Rc<ReaderOwner>>,
        retired_reader: Option<Weak<ReaderOwner>>,
        next_raster_epoch: u64,
        pub(crate) generation: u64,
        pending: Option<acquire_fence::Pending<*mut media::AImage>>,
        acquire_quarantined: bool,
    }
    impl Producer {
        pub(crate) unsafe fn start(
            app: &android_activity::AndroidApp,
            generation: u64,
        ) -> Result<Self, String> {
            Self::start_with_extent(
                app,
                generation,
                super::super::native_panel_resolution::RasterExtent::BASE,
            )
        }
        /// Startup only. A caller must retire Vulkan imports/fences and stop the old writer
        /// before replacing its reader; this API never mutates a live image generation.
        pub(crate) unsafe fn start_with_extent(
            app: &android_activity::AndroidApp,
            generation: u64,
            extent: super::super::native_panel_resolution::RasterExtent,
        ) -> Result<Self, String> {
            let reader = new_reader(extent, 1)?;
            let mut window = ptr::null_mut();
            if media::AImageReader_getWindow(reader.reader, &mut window) != 0 {
                reader.writer_detached.set(true);
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
                        JValue::Int(extent.width as i32),
                        JValue::Int(extent.height as i32),
                        JValue::Long(generation as i64),
                    ],
                )?;
                Ok(())
            });
            if let Err(error) = result {
                // Exceptional return still joins any possibly queued Java start.
                let owner = Self {
                    reader,
                    resize: None,
                    retired_reader: None,
                    next_raster_epoch: 2,
                    generation,
                    pending: None,
                    acquire_quarantined: false,
                };
                let _ = owner.stop(app);
                return Err(error);
            }
            Ok(Self {
                reader,
                resize: None,
                retired_reader: None,
                next_raster_epoch: 2,
                generation,
                pending: None,
                acquire_quarantined: false,
            })
        }
        pub(crate) fn raster_extent(&self) -> RasterExtent {
            self.reader.extent
        }
        pub(crate) fn resize_pending(&self) -> bool {
            self.resize.is_some()
        }
        fn quarantine_readers(&mut self) {
            self.acquire_quarantined = true;
            self.reader.quarantined.set(true);
            if let Some(reader) = &self.resize {
                reader.quarantined.set(true);
            }
        }
        /// Single in-flight raster transition, independent from control/UI generation.
        /// Java owns the Surface wrapper only when requestResize returns true.
        pub(crate) unsafe fn request_resize(
            &mut self,
            app: &android_activity::AndroidApp,
            extent: RasterExtent,
        ) -> Result<bool, String> {
            if self.acquire_quarantined
                || self.resize.is_some()
                || extent == self.reader.extent
                || self
                    .retired_reader
                    .as_ref()
                    .and_then(Weak::upgrade)
                    .is_some()
            {
                return Ok(false);
            }
            let epoch = self.next_raster_epoch;
            if epoch > i64::MAX as u64 {
                return Err("mesh UI raster epoch exhausted".into());
            }
            self.next_raster_epoch = epoch
                .checked_add(1)
                .ok_or("mesh UI raster epoch exhausted")?;
            let reader = new_reader(extent, epoch)?;
            let mut window = ptr::null_mut();
            if media::AImageReader_getWindow(reader.reader, &mut window) != 0 || window.is_null() {
                reader.writer_detached.set(true);
                return Err("mesh UI resize reader window".into());
            }
            self.resize = Some(reader.clone());
            let mut admitted = false;
            let result = with_class(app, |env, class, _| {
                let raw = ANativeWindow_toSurface(env.get_raw(), window);
                let surface = env.as_cast_raw::<JObject>(&raw)?;
                admitted = env
                    .call_static_method(
                        class,
                        jni_str!("requestResize"),
                        jni_sig!("(JJLandroid/view/Surface;II)Z"),
                        &[
                            JValue::Long(self.generation as i64),
                            JValue::Long(epoch as i64),
                            JValue::Object(&surface),
                            JValue::Int(extent.width as i32),
                            JValue::Int(extent.height as i32),
                        ],
                    )?
                    .z()?;
                if !admitted {
                    env.call_method(&surface, jni_str!("release"), jni_sig!("()V"), &[])?;
                }
                Ok(())
            });
            if let Err(error) = result {
                // Invocation may have reached Java; neither Surface/reader ownership is proven.
                self.quarantine_readers();
                return Err(error);
            }
            if !admitted {
                reader.writer_detached.set(true);
                self.resize = None;
                return Ok(false);
            }
            crate::marker("native-mesh-ui", format!("event=raster-resize-request generation={} rasterEpoch={} rasterWidth={} rasterHeight={} evidence=request-only", self.generation, epoch, extent.width, extent.height));
            Ok(true)
        }
        /// Copy a published UI-thread snapshot. Null retires native pixels; empty means unchanged.
        pub(crate) fn read_native_page(
            &self,
            app: &android_activity::AndroidApp,
            revision: u64,
            atlas_epoch: u64,
        ) -> Result<Option<Vec<u8>>, String> {
            let mut packet = None;
            with_class(app, |env, class, _| {
                let result = env
                    .call_static_method(
                        class,
                        jni_str!("readNativePage"),
                        jni_sig!("(JJJ)[B"),
                        &[
                            JValue::Long(self.generation as i64),
                            JValue::Long(revision as i64),
                            JValue::Long(atlas_epoch as i64),
                        ],
                    )?
                    .l()?;
                if !result.is_null() {
                    let bytes = env.cast_local::<jni::objects::JByteArray>(result)?;
                    if bytes.len(env)? > crate::native_page::MAX_PACKET {
                        return Err(jni::errors::Error::JniCall(
                            jni::errors::JniError::InvalidArguments,
                        ));
                    }
                    packet = Some(env.convert_byte_array(&bytes)?);
                }
                Ok(())
            })?;
            Ok(packet)
        }
        /// Returns writer commitment only; the first acquired/imported/submitted image is later.
        pub(crate) unsafe fn poll_resize(
            &mut self,
            app: &android_activity::AndroidApp,
        ) -> Result<Option<RasterExtent>, String> {
            if self.acquire_quarantined {
                return Ok(None);
            }
            let Some(candidate) = self.resize.as_ref() else {
                return Ok(None);
            };
            let epoch = candidate.epoch;
            let mut status = 0;
            let result = with_class(app, |env, class, _| {
                status = env
                    .call_static_method(
                        class,
                        jni_str!("resizeStatus"),
                        jni_sig!("(JJ)I"),
                        &[
                            JValue::Long(self.generation as i64),
                            JValue::Long(epoch as i64),
                        ],
                    )?
                    .i()?;
                Ok(())
            });
            if let Err(error) = result {
                self.quarantine_readers();
                return Err(error);
            }
            match status {
                0 => Ok(None),
                1 => {
                    // The old pending image was never imported; transfer its OWN acquire FD.
                    if let Some(pending) = self.pending.take() {
                        media::AImage_deleteAsync(pending.image, pending.fence);
                    }
                    let next = self.resize.take().unwrap();
                    let old = std::mem::replace(&mut self.reader, next);
                    old.writer_detached.set(true);
                    self.retired_reader = Some(Rc::downgrade(&old));
                    let extent = self.reader.extent;
                    crate::marker("native-mesh-ui", format!("event=raster-resize-writer-ack generation={} rasterEpoch={} rasterWidth={} rasterHeight={} evidence=writer-commit visibleProof=false", self.generation, epoch, extent.width, extent.height));
                    Ok(Some(extent))
                }
                -1 => {
                    if let Some(candidate) = self.resize.take() {
                        candidate.writer_detached.set(true);
                    }
                    crate::marker("native-mesh-ui", format!("event=raster-resize-rejected generation={} rasterEpoch={} oldWriterRetained=true", self.generation, epoch));
                    Ok(None)
                }
                _ => {
                    self.quarantine_readers();
                    Err("mesh UI raster switch uncertain; both readers retained and adaptation disabled".into())
                }
            }
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
                        if AImageReader_acquireNextImageAsync(
                            self.reader.reader,
                            &mut image,
                            &mut fence,
                        ) != 0
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
                    self.reader.quarantined.set(true);
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
                Ok(buffer) => Some(ImageLease {
                    image,
                    buffer,
                    raster_extent: self.reader.extent,
                    raster_epoch: self.reader.epoch,
                    _reader: self.reader.clone(),
                }),
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
                self.reader.writer_detached.set(true);
                if let Some(candidate) = &self.resize {
                    candidate.writer_detached.set(true);
                }
            } else {
                self.quarantine_readers();
            }
            result
        }
    }
    impl Producer {
        /// Quiesce Java without recycling images whose Vulkan release failed.
        pub(crate) unsafe fn quiesce(mut self, app: &android_activity::AndroidApp) {
            // Pending raw images remain owned by the deliberately retained reader closure.
            // No uncertain FD is closed or retransferred during this fail-safe retention path.
            self.quarantine_readers();
            deactivate(self.generation);
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
    #[cfg(feature = "showcase-vulkan-ui")]
    #[test]
    fn showcase_ui_cannot_project_neutral_diagnostic_stimulus_controls() {
        assert!(!neutral_controls_enabled());
        assert!(neutral_frame_controls(Some(ControlSnapshot::default())).is_none());
    }
    #[test]
    fn recenter_uses_current_head_preserves_size_and_rejects_stale_generation() {
        let mut state = ControlSnapshot {
            generation: 9,
            pose_initialized: true,
            ..ControlSnapshot::default()
        };
        state.pose.width = 1.2;
        state.pose.height = 0.9;
        assert!(!recenter(
            &mut state,
            8,
            [2.0, 1.5, 0.0],
            [0.0, 0.0, 0.0, 1.0]
        ));
        assert!(!recenter(
            &mut state,
            9,
            [f32::NAN, 0.0, 0.0],
            [0.0, 0.0, 0.0, 1.0]
        ));
        assert!(recenter(
            &mut state,
            9,
            [2.0, 1.5, 0.0],
            [0.0, 0.0, 0.0, 1.0]
        ));
        assert_eq!(state.pose.center, [2.0, 1.5, -1.4]);
        assert_eq!((state.pose.width, state.pose.height), (1.2, 0.9));
        assert_eq!(state.pose_revision, 1);
    }
    #[test]
    fn recenter_and_original_reset_atomically_release_wrist_attachment() {
        let mut s = ControlSnapshot {
            generation: 9,
            pose_initialized: true,
            attachment: "WRIST",
            tracking_valid: false,
            ..Default::default()
        };
        let old = s.pose;
        assert!(!recenter(&mut s, 8, [0.0; 3], [0.0, 0.0, 0.0, 1.0]));
        assert_eq!(s.pose, old);
        assert_eq!(s.attachment, "WRIST");
        assert!(!s.tracking_valid);
        assert_eq!(s.revision, 0);
        assert!(recenter(&mut s, 9, [1.0; 3], [0.0, 0.0, 0.0, 1.0]));
        assert_eq!(s.attachment, "WORLD");
        assert!(s.tracking_valid);
        assert_eq!(s.revision, 1);
        assert_eq!(s.pose_revision, 1);
        s.attachment = "WRIST";
        s.tracking_valid = false;
        assert!(!panel_action(&mut s, 8, 13));
        assert_eq!(s.attachment, "WRIST");
        assert!(!s.tracking_valid);
        assert!(panel_action(&mut s, 9, 13));
        assert_eq!(s.pose, s.initial_pose);
        assert_eq!(s.attachment, "WORLD");
        assert!(s.tracking_valid);
        assert_eq!(s.revision, 2);
        assert_eq!(s.pose_revision, 2);
    }
    #[test]
    fn native_chrome_only_admits_current_generation_presentation_actions() {
        let mut state = ControlSnapshot {
            generation: 7,
            pose_initialized: true,
            ..ControlSnapshot::default()
        };
        let initial = state.initial_pose;
        assert!(!panel_action(&mut state, 6, 7));
        for code in [1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 99] {
            assert!(!panel_action(&mut state, 7, code));
        }
        assert_eq!(state.revision, 0);
        assert!(panel_action(&mut state, 7, 7));
        assert_eq!(state.pose.width, 1.2);
        assert_eq!(state.pose_revision, 1);
        assert!(panel_action(&mut state, 7, 13));
        assert_eq!(state.pose.width, initial.width);
        assert_eq!(state.pose.center, initial.center);
        assert_eq!(state.pose_revision, 2);
    }

    #[test]
    fn original_overlay_is_explicit_and_does_not_enable_scene_depth_or_other_modes() {
        let original = "solid-black-private-particles";
        let scene = "native-worldspace-integration";
        assert!(render_selection(original, true, true, false));
        assert!(!render_selection(original, true, true, true));
        assert!(!render_selection(original, false, true, false));
        assert!(!render_selection(original, true, false, false));
        assert!(render_selection(scene, true, false, true));
        assert!(!render_selection(scene, true, false, false));
        assert!(!render_selection(scene, true, true, true));
        assert!(!mesh_selection("custom-stereo-projection", true, true));
        assert!(!mesh_selection("solid-black-hands-and-grafts", true, true));
    }
    #[test]
    fn session_presentation_uses_the_submitted_tuple_not_a_visibility_setter() {
        let mut state = ControlSnapshot {
            generation: 91,
            pose_initialized: true,
            ..ControlSnapshot::default()
        };
        let visible = state;
        assert_eq!(submitted_visibility(state, visible), None);
        observe_visibility(&mut state, visible);
        assert_eq!(submitted_visibility(state, visible), Some(true));
        set_visibility(&mut state, 91, false).unwrap();
        let hidden = state;
        // A newer hide request is not evidence that the old visible frame was hidden.
        assert_eq!(submitted_visibility(state, visible), Some(true));
        assert_eq!(submitted_visibility(state, hidden), None);
        observe_visibility(&mut state, hidden);
        assert_eq!(submitted_visibility(state, hidden), Some(false));
        assert_eq!(submitted_visibility(state, visible), None);
    }
    #[test]
    fn retired_or_uninitialized_panel_cannot_activate_session_presentation() {
        let mut state = ControlSnapshot {
            generation: 92,
            pose_initialized: true,
            ..ControlSnapshot::default()
        };
        set_visibility(&mut state, 92, false).unwrap();
        let hidden = state;
        observe_visibility(&mut state, hidden);
        assert_eq!(submitted_visibility(state, hidden), Some(false));
        assert_eq!(
            submitted_visibility(
                state,
                ControlSnapshot {
                    pose_initialized: false,
                    ..hidden
                }
            ),
            None
        );
        state.generation = 93;
        assert_eq!(submitted_visibility(state, hidden), None);
        state.generation = 0;
        assert_eq!(submitted_visibility(state, hidden), None);
    }
    #[test]
    fn original_panel_filters_demo_settings_but_retains_visibility_frame() {
        let frame = ControlSnapshot {
            generation: 7,
            ..ControlSnapshot::default()
        };
        let neutral = neutral_frame_controls(Some(frame));
        assert_eq!(neutral.is_some(), neutral_controls_enabled());
        assert!(neutral_frame_controls(None).is_none());
        let mut owner = frame;
        let revision = set_visibility(&mut owner, 7, false).unwrap();
        let submitted = owner;
        assert!(observe_visibility(&mut owner, submitted));
        assert_eq!(visibility_status(owner, 7, revision), "applied");
        assert!(!owner.effective_panel_visible);
        // Filtering the demo snapshot neither changes nor consumes the frame
        // that establishes effective visibility and dispatches real panel input.
        assert_eq!(frame.generation, owner.generation);
        assert_eq!(frame.particle_scale, 0.5);
    }
    #[test]
    fn geometry_requires_current_submitted_pose_and_exact_reference() {
        let mut state = ControlSnapshot {
            generation: 7,
            ..Default::default()
        };
        assert_eq!(geometry_readback(state, 7)["status"], "uninitialized");
        assert!(calibrate(&mut state, 7, [1., 2., 3.], [0., 0., 0., 1.]));
        assert_eq!(geometry_readback(state, 7)["status"], "pending");
        assert!(geometry_readback(state, 7).get("center").is_none());
        let first = state;
        assert!(!observe_geometry(&mut state, first, 0, "LOCAL_FLOOR"));
        assert!(observe_geometry(&mut state, first, 0, "LOCAL"));
        let seen = geometry_readback(state, 7);
        assert_eq!(seen["status"], "applied");
        assert_eq!(seen["submitted_frame"], 0);
        assert_eq!(seen["reference_space"], "LOCAL");
        assert!((seen["center"][2].as_f64().unwrap() - 1.6).abs() < 0.001);
        assert!(apply(&mut state, 7, 6, 1.));
        assert_eq!(geometry_readback(state, 7)["status"], "pending");
        assert!(geometry_readback(state, 7).get("center").is_none());
        let moved = state;
        assert!(observe_geometry(&mut state, moved, 4, "STAGE"));
        assert!(!observe_geometry(&mut state, first, 3, "LOCAL"));
        assert_eq!(geometry_readback(state, 7)["reference_space"], "STAGE");
        assert_eq!(geometry_readback(state, 8)["status"], "unavailable");
        assert!(geometry_readback(state, 8).get("center").is_none());
        state.generation = 0;
        assert!(!observe_geometry(&mut state, moved, 5, "STAGE"));
        assert_eq!(geometry_readback(state, 7)["status"], "unavailable");
    }

    #[test]
    fn widget_pixel_inverse_matches_actual_native_ray_mapping() {
        let mut state = ControlSnapshot {
            generation: 7,
            ..Default::default()
        };
        let half = std::f32::consts::FRAC_1_SQRT_2;
        assert!(calibrate(
            &mut state,
            7,
            [2., 1.65, 3.],
            [0., half, 0., half]
        ));
        for pixels in [[10., 20.], [512., 384.], [1000., 750.]] {
            let pose = state.pose;
            let normal = cross(pose.right, pose.up);
            let target: [f32; 3] = std::array::from_fn(|i| {
                pose.center[i]
                    + pose.right[i] * pose.width * (pixels[0] / WIDTH as f32 - 0.5)
                    + pose.up[i] * pose.height * (0.5 - pixels[1] / HEIGHT as f32)
            });
            let origin = std::array::from_fn(|i| target[i] + normal[i] * 0.4);
            let mapped = ray_pixels(pose, origin, normal.map(|x| -x)).unwrap();
            assert!((mapped[0] - pixels[0]).abs() < 0.001 && (mapped[1] - pixels[1]).abs() < 0.001);
        }
    }
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
    fn owner_or_focus_cancellation_requires_valid_open_before_held_reentry() {
        let mut c = PointerCapture::default();
        let p = Some([40.0, 20.0]);
        assert_eq!(c.update(true, true, p), Some((0, [40.0, 20.0])));
        assert_eq!(c.update(false, false, None), Some((3, [40.0, 20.0])));
        assert_eq!(c.update(true, true, p), None);
        assert_eq!(c.update(false, false, None), None);
        assert_eq!(c.update(true, true, p), None);
        assert_eq!(c.update(true, false, p), None);
        assert_eq!(c.update(true, true, p), Some((0, [40.0, 20.0])));
        assert_eq!(
            c.update(true, true, Some([2000.0, -10.0])),
            Some((1, [1023.0, 0.0]))
        );
        assert_eq!(c.update(true, false, None), Some((2, [1023.0, 0.0])));
        assert!(!c.captured());
    }
    #[test]
    fn presentation_setters_validate_and_revision_only_actual_changes() {
        let mut s = ControlSnapshot {
            generation: 7,
            ..Default::default()
        };
        assert!(!panel_width(&mut s, 7, 0.96));
        assert!(calibrate(&mut s, 7, [0.0; 3], [0.0, 0.0, 0.0, 1.0]));
        let revision = s.revision;
        for (g, w) in [
            (0, 0.96),
            (8, 0.96),
            (7, f32::NAN),
            (7, f32::INFINITY),
            (7, 0.479),
            (7, 1.441),
        ] {
            assert!(!panel_width(&mut s, g, w));
            assert_eq!(s.revision, revision);
        }
        for w in [0.48, 1.44] {
            assert!(panel_width(&mut s, 7, w));
            assert_eq!(s.pose.width, w);
            assert_eq!(s.pose.height, w * 0.75);
            assert_eq!(s.pose_revision, s.revision);
            let r = s.revision;
            assert!(panel_width(&mut s, 7, w));
            assert_eq!(s.revision, r);
        }
        let pose = s.pose;
        let r = s.revision;
        for (g, mode) in [(0, "WORLD"), (8, "WORLD"), (7, "INVALID")] {
            assert!(!attachment_pose(&mut s, g, pose, mode, true));
            assert_eq!(s.revision, r);
        }
        let mut bad = pose;
        bad.center[0] = f32::NAN;
        assert!(!attachment_pose(&mut s, 7, bad, "WRIST", true));
        assert_eq!(s.revision, r);
        assert!(attachment_pose(&mut s, 7, pose, "WRIST", true));
        let r = s.revision;
        assert!(attachment_pose(&mut s, 7, pose, "WRIST", true));
        assert_eq!(s.revision, r);
        assert!(attachment_pose(&mut s, 7, pose, "WRIST", false));
        assert_eq!(s.revision, r + 1);
        assert_eq!(s.pose, pose);
        assert!(!s.tracking_valid);
        assert_eq!(s.pose_revision, s.revision);
        assert!(attachment_pose(&mut s, 7, pose, "WORLD", true));
        assert!(s.tracking_valid);
        s.revision = i64::MAX as u64;
        assert!(!panel_width(&mut s, 7, 0.96));
        assert!(!attachment_pose(&mut s, 7, pose, "WORLD", true));
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
