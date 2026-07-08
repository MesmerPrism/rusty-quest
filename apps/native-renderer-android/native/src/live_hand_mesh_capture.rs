//! Runtime-controlled OpenXR FB hand mesh capture.
//!
//! This writes the same public hand capture artifact shape consumed by the
//! recorded replay build path: immutable FB bind mesh rigs, compact joint
//! clips, and sampled CPU-skinned validation mesh frames.

use std::{
    fs::{self, File, OpenOptions},
    io::{BufWriter, Write},
    path::{Path, PathBuf},
    ptr,
    time::{SystemTime, UNIX_EPOCH},
};

use openxr as xr;
use serde_json::json;

use crate::native_renderer_visual_options::HandMeshVisualMaterialSettings;

const CONTROL_FILE_NAME: &str = "hand-mesh-capture-control.json";
const CAPTURE_ROOT_NAME: &str = "hand-mesh-captures";
const CONTROL_SCHEMA: &str = "rusty.quest.native_renderer.hand_mesh_capture_control.v1";
const MANIFEST_SCHEMA: &str = "rusty.quest.native_renderer.hand_mesh_capture_manifest.v1";
const RIG_SCHEMA: &str = "rusty.matter.hand_mesh_rig.v1";
const CLIP_ROW_SCHEMA: &str = "rusty.matter.hand_joint_frame.v1";
const VALIDATION_ROW_SCHEMA: &str = "rusty.matter.hand_validation_frame.v1";
const STATUS_ROW_SCHEMA: &str = "rusty.quest.native_renderer.hand_mesh_capture_status.v1";
const DEFAULT_MAX_FRAMES: u64 = 900;
const DEFAULT_SAMPLE_PERIOD_FRAMES: u64 = 1;
const DEFAULT_VALIDATION_SAMPLE_PERIOD_FRAMES: u64 = 6;
const HAND_JOINT_COUNT: usize = xr::HAND_JOINT_COUNT;
const TIP_LENGTH_COUNT: usize = 5;

const RUNTIME_JOINTS: [xr::HandJoint; 21] = [
    xr::HandJoint::PALM,
    xr::HandJoint::WRIST,
    xr::HandJoint::THUMB_METACARPAL,
    xr::HandJoint::THUMB_PROXIMAL,
    xr::HandJoint::THUMB_DISTAL,
    xr::HandJoint::INDEX_METACARPAL,
    xr::HandJoint::INDEX_PROXIMAL,
    xr::HandJoint::INDEX_INTERMEDIATE,
    xr::HandJoint::INDEX_DISTAL,
    xr::HandJoint::MIDDLE_METACARPAL,
    xr::HandJoint::MIDDLE_PROXIMAL,
    xr::HandJoint::MIDDLE_INTERMEDIATE,
    xr::HandJoint::MIDDLE_DISTAL,
    xr::HandJoint::RING_METACARPAL,
    xr::HandJoint::RING_PROXIMAL,
    xr::HandJoint::RING_INTERMEDIATE,
    xr::HandJoint::RING_DISTAL,
    xr::HandJoint::LITTLE_METACARPAL,
    xr::HandJoint::LITTLE_PROXIMAL,
    xr::HandJoint::LITTLE_INTERMEDIATE,
    xr::HandJoint::LITTLE_DISTAL,
];

const TIP_PAIRS: [(xr::HandJoint, xr::HandJoint); TIP_LENGTH_COUNT] = [
    (xr::HandJoint::THUMB_DISTAL, xr::HandJoint::THUMB_TIP),
    (xr::HandJoint::INDEX_DISTAL, xr::HandJoint::INDEX_TIP),
    (xr::HandJoint::MIDDLE_DISTAL, xr::HandJoint::MIDDLE_TIP),
    (xr::HandJoint::RING_DISTAL, xr::HandJoint::RING_TIP),
    (xr::HandJoint::LITTLE_DISTAL, xr::HandJoint::LITTLE_TIP),
];

const OPENXR_JOINT_NAMES: [&str; HAND_JOINT_COUNT] = [
    "palm_ext",
    "wrist_ext",
    "thumb_metacarpal_ext",
    "thumb_proximal_ext",
    "thumb_distal_ext",
    "thumb_tip_ext",
    "index_metacarpal_ext",
    "index_proximal_ext",
    "index_intermediate_ext",
    "index_distal_ext",
    "index_tip_ext",
    "middle_metacarpal_ext",
    "middle_proximal_ext",
    "middle_intermediate_ext",
    "middle_distal_ext",
    "middle_tip_ext",
    "ring_metacarpal_ext",
    "ring_proximal_ext",
    "ring_intermediate_ext",
    "ring_distal_ext",
    "ring_tip_ext",
    "little_metacarpal_ext",
    "little_proximal_ext",
    "little_intermediate_ext",
    "little_distal_ext",
    "little_tip_ext",
];

pub(crate) struct LiveHandMeshCaptureRecorder {
    control_path: Option<PathBuf>,
    capture_root: Option<PathBuf>,
    source: OpenXrHandMeshCaptureSource,
    current: Option<LiveHandMeshCaptureSession>,
    last_control_error: Option<String>,
}

impl LiveHandMeshCaptureRecorder {
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn new(
        app: &android_activity::AndroidApp,
        instance: &xr::Instance,
        system: xr::SystemId,
        session: &xr::Session<xr::Vulkan>,
        extension_available: bool,
        extension_enabled: bool,
        material_settings: HandMeshVisualMaterialSettings,
    ) -> Self {
        let source = OpenXrHandMeshCaptureSource::new(
            instance,
            system,
            session,
            extension_available,
            extension_enabled,
        );
        let Some(data_path) = app.external_data_path() else {
            crate::marker(
                "hand-mesh-capture",
                "status=unavailable reason=missing-external-app-data-path controlSchema=rusty.quest.native_renderer.hand_mesh_capture_control.v1 captureSchema=rusty.quest.native_renderer.hand_mesh_capture_manifest.v1 runtimeProvider=XR_EXT_hand_tracking+XR_FB_hand_tracking_mesh",
            );
            return Self {
                control_path: None,
                capture_root: None,
                source,
                current: None,
                last_control_error: None,
            };
        };

        let capture_root = data_path.join(CAPTURE_ROOT_NAME);
        let control_path = data_path.join(CONTROL_FILE_NAME);
        if let Err(error) = fs::create_dir_all(&capture_root) {
            crate::marker(
                "hand-mesh-capture",
                format!(
                    "status=unavailable reason=create-capture-root-failed error={} captureRoot={}",
                    crate::sanitize(&error.to_string()),
                    crate::sanitize(&path_marker(&capture_root))
                ),
            );
            return Self {
                control_path: None,
                capture_root: None,
                source,
                current: None,
                last_control_error: None,
            };
        }

        crate::marker(
            "hand-mesh-capture",
            format!(
                "status=ready controlSchema={} captureSchema={} rigSchema={} clipRowSchema={} validationRowSchema={} controlFile={} captureRoot={} openxrHandTrackingExtensionAvailable={} openxrHandTrackingMeshExtensionAvailable={} openxrHandTrackingMeshExtensionEnabled={} meshRigsReady={} leftRigReady={} rightRigReady={} materialProfile={} materialAlpha={:.2} materialRimStrength={:.2} materialWireframeEnabled={} materialWireframeWidthPx={:.2}",
                CONTROL_SCHEMA,
                MANIFEST_SCHEMA,
                RIG_SCHEMA,
                CLIP_ROW_SCHEMA,
                VALIDATION_ROW_SCHEMA,
                crate::sanitize(&path_marker(&control_path)),
                crate::sanitize(&path_marker(&capture_root)),
                source.hand_tracking_extension_available,
                source.hand_mesh_extension_available,
                source.hand_mesh_extension_enabled,
                source.rigs_ready(),
                source.left.is_some(),
                source.right.is_some(),
                material_settings.profile.marker_value(),
                material_settings.alpha,
                material_settings.rim_strength,
                material_settings.wireframe_enabled,
                material_settings.wireframe_width_px,
            ),
        );

        Self {
            control_path: Some(control_path),
            capture_root: Some(capture_root),
            source,
            current: None,
            last_control_error: None,
        }
    }

    pub(crate) fn update_and_record(
        &mut self,
        reference_space: &xr::Space,
        predicted_display_time: xr::Time,
        renderer_frame: u64,
        material_settings: HandMeshVisualMaterialSettings,
    ) {
        let Some(control_path) = self.control_path.as_ref() else {
            return;
        };
        let Some(capture_root) = self.capture_root.as_ref() else {
            return;
        };
        let control = match read_control(control_path) {
            Ok(control) => control,
            Err(ControlReadError::Missing) => {
                if let Some(session) = self.current.as_mut() {
                    session.finish("control-file-missing");
                }
                self.current = None;
                return;
            }
            Err(ControlReadError::Malformed(error)) => {
                if self.last_control_error.as_deref() != Some(error.as_str()) {
                    crate::marker(
                        "hand-mesh-capture",
                        format!(
                            "status=control-error reason=malformed-control-file error={} controlFile={}",
                            crate::sanitize(&error),
                            crate::sanitize(&path_marker(control_path))
                        ),
                    );
                    self.last_control_error = Some(error);
                }
                return;
            }
        };
        self.last_control_error = None;
        if !control.enabled {
            if let Some(session) = self.current.as_mut() {
                session.finish("control-disabled");
            }
            self.current = None;
            return;
        }

        if !self.source.rigs_ready() {
            if let Some(session) = self.current.as_mut() {
                session.finish("mesh-rigs-unavailable");
            }
            self.current = None;
            crate::marker(
                "hand-mesh-capture",
                format!(
                    "status=start-blocked reason=mesh-rigs-unavailable captureId={} sourceStatus={} openxrHandTrackingMeshExtensionEnabled={}",
                    crate::sanitize(&control.session_id),
                    crate::sanitize(self.source.status.as_deref().unwrap_or("unknown")),
                    self.source.hand_mesh_extension_enabled
                ),
            );
            return;
        }

        let should_start = self
            .current
            .as_ref()
            .map_or(true, |session| session.session_id != control.session_id);
        if should_start {
            if let Some(session) = self.current.as_mut() {
                session.finish("session-replaced");
            }
            self.current = match LiveHandMeshCaptureSession::start(
                capture_root,
                control,
                &self.source,
                material_settings,
            ) {
                Ok(session) => Some(session),
                Err(error) => {
                    crate::marker(
                        "hand-mesh-capture",
                        format!(
                            "status=start-error reason={} captureRoot={}",
                            crate::sanitize(&error),
                            crate::sanitize(&path_marker(capture_root))
                        ),
                    );
                    None
                }
            };
        }

        if let Some(session) = self.current.as_mut() {
            session.record_frame(
                &mut self.source,
                reference_space,
                predicted_display_time,
                renderer_frame,
                material_settings,
            );
            if session.frame_limit_reached() {
                session.finish("max-frames-reached");
                self.current = None;
            }
        }
    }

    pub(crate) fn finish_active(&mut self, reason: &'static str) {
        if let Some(session) = self.current.as_mut() {
            session.finish(reason);
        }
        self.current = None;
    }
}

struct OpenXrHandMeshCaptureSource {
    hand_tracking_extension_available: bool,
    hand_mesh_extension_available: bool,
    hand_mesh_extension_enabled: bool,
    hand_tracking_system_supported: bool,
    left: Option<TrackedHandMesh>,
    right: Option<TrackedHandMesh>,
    frame_counter: u32,
    status: Option<String>,
}

impl OpenXrHandMeshCaptureSource {
    fn new(
        instance: &xr::Instance,
        system: xr::SystemId,
        session: &xr::Session<xr::Vulkan>,
        hand_tracking_extension_available: bool,
        hand_mesh_extension_enabled: bool,
    ) -> Self {
        let hand_mesh_extension_available = instance.exts().fb_hand_tracking_mesh.is_some();
        let mut source = Self {
            hand_tracking_extension_available,
            hand_mesh_extension_available,
            hand_mesh_extension_enabled,
            hand_tracking_system_supported: false,
            left: None,
            right: None,
            frame_counter: 0,
            status: None,
        };

        if !hand_mesh_extension_enabled {
            source.status = Some("XR_FB_hand_tracking_mesh-extension-not-enabled".to_string());
            crate::marker(
                "hand-mesh-capture",
                format!(
                    "status=disabled reason=XR_FB_hand_tracking_mesh-extension-not-enabled extensionAvailable={} extensionEnabled=false",
                    hand_mesh_extension_available
                ),
            );
            return source;
        }
        if instance.exts().fb_hand_tracking_mesh.is_none() {
            source.status = Some("XR_FB_hand_tracking_mesh-functions-not-loaded".to_string());
            crate::marker(
                "hand-mesh-capture",
                "status=unavailable reason=XR_FB_hand_tracking_mesh-functions-not-loaded",
            );
            return source;
        }
        match instance.supports_hand_tracking(system) {
            Ok(supported) => source.hand_tracking_system_supported = supported,
            Err(error) => {
                source.status = Some(format!("supports_hand_tracking-failed-{error}"));
                crate::marker(
                    "hand-mesh-capture",
                    format!(
                        "status=unavailable reason={} openxrHandTrackingMeshExtensionEnabled=true",
                        crate::sanitize(source.status.as_deref().unwrap_or("unknown"))
                    ),
                );
                return source;
            }
        }
        if !source.hand_tracking_system_supported {
            source.status = Some("system-does-not-support-hand-tracking".to_string());
            crate::marker(
                "hand-mesh-capture",
                "status=unsupported reason=system-does-not-support-hand-tracking openxrHandTrackingMeshExtensionEnabled=true",
            );
            return source;
        }

        source.left = TrackedHandMesh::load(instance, session, xr::Hand::LEFT, "left").ok();
        source.right = TrackedHandMesh::load(instance, session, xr::Hand::RIGHT, "right").ok();
        source.status = Some(if source.rigs_ready() {
            "ready".to_string()
        } else {
            "one-or-more-rigs-unavailable".to_string()
        });
        crate::marker(
            "hand-mesh-capture",
            format!(
                "status={} runtimeProvider=XR_EXT_hand_tracking+XR_FB_hand_tracking_mesh leftRigReady={} rightRigReady={} leftTopologyKey={} rightTopologyKey={}",
                source.status.as_deref().unwrap_or("unknown"),
                source.left.is_some(),
                source.right.is_some(),
                source.left.as_ref().map(|hand| hand.rig.topology_key.as_str()).unwrap_or("none"),
                source.right.as_ref().map(|hand| hand.rig.topology_key.as_str()).unwrap_or("none"),
            ),
        );
        source
    }

    fn rigs_ready(&self) -> bool {
        self.left.is_some() && self.right.is_some()
    }

    fn locate_frames(
        &mut self,
        reference_space: &xr::Space,
        predicted_display_time: xr::Time,
    ) -> LocatedMeshFrames {
        self.frame_counter = self.frame_counter.wrapping_add(1);
        let frame_index = self.frame_counter;
        let timestamp_ns = predicted_display_time.as_nanos().max(0) as u64;
        LocatedMeshFrames {
            left: self.left.as_ref().and_then(|hand| {
                locate_hand_mesh_frame(
                    hand,
                    reference_space,
                    predicted_display_time,
                    frame_index,
                    timestamp_ns,
                )
                .map_err(|error| {
                    crate::marker(
                        "hand-mesh-capture",
                        format!(
                            "status=locate-warning handedness=left reason={}",
                            crate::sanitize(&error)
                        ),
                    );
                })
                .ok()
            }),
            right: self.right.as_ref().and_then(|hand| {
                locate_hand_mesh_frame(
                    hand,
                    reference_space,
                    predicted_display_time,
                    frame_index,
                    timestamp_ns,
                )
                .map_err(|error| {
                    crate::marker(
                        "hand-mesh-capture",
                        format!(
                            "status=locate-warning handedness=right reason={}",
                            crate::sanitize(&error)
                        ),
                    );
                })
                .ok()
            }),
        }
    }
}

struct TrackedHandMesh {
    tracker: xr::HandTracker,
    rig: HandMeshRig,
}

impl TrackedHandMesh {
    fn load(
        instance: &xr::Instance,
        session: &xr::Session<xr::Vulkan>,
        hand: xr::Hand,
        handedness: &'static str,
    ) -> Result<Self, String> {
        let tracker = session
            .create_hand_tracker(hand)
            .map_err(|error| format!("{handedness} create_hand_tracker: {error}"))?;
        let mesh = load_fb_hand_mesh(instance, &tracker, handedness)?;
        Ok(Self { tracker, rig: mesh })
    }
}

struct HandMeshRig {
    handedness: &'static str,
    topology_key: String,
    joint_bind_poses: Vec<xr::sys::Posef>,
    joint_radii: Vec<f32>,
    joint_parents: Vec<xr::HandJoint>,
    vertex_positions: Vec<xr::sys::Vector3f>,
    vertex_normals: Vec<xr::sys::Vector3f>,
    vertex_uvs: Vec<xr::sys::Vector2f>,
    vertex_blend_indices: Vec<xr::sys::Vector4sFB>,
    vertex_blend_weights: Vec<xr::sys::Vector4f>,
    indices: Vec<i16>,
}

fn load_fb_hand_mesh(
    instance: &xr::Instance,
    tracker: &xr::HandTracker,
    handedness: &'static str,
) -> Result<HandMeshRig, String> {
    let mesh_ext = instance
        .exts()
        .fb_hand_tracking_mesh
        .as_ref()
        .ok_or_else(|| "XR_FB_hand_tracking_mesh function table unavailable".to_string())?;

    let mut query = empty_mesh_query();
    let result = unsafe { (mesh_ext.get_hand_mesh)(tracker.as_raw(), &mut query) };
    if result != xr::sys::Result::SUCCESS && result != xr::sys::Result::ERROR_SIZE_INSUFFICIENT {
        return Err(format!(
            "{handedness} xrGetHandMeshFB query failed: {result:?}"
        ));
    }
    let joint_count = query.joint_count_output as usize;
    let vertex_count = query.vertex_count_output as usize;
    let index_count = query.index_count_output as usize;
    if joint_count == 0 || vertex_count == 0 || index_count == 0 {
        return Err(format!(
            "{handedness} xrGetHandMeshFB returned empty counts joints={joint_count} vertices={vertex_count} indices={index_count}"
        ));
    }

    let mut joint_bind_poses = vec![xr::sys::Posef::default(); joint_count];
    let mut joint_radii = vec![0.0_f32; joint_count];
    let mut joint_parents = vec![xr::HandJoint::PALM; joint_count];
    let mut vertex_positions = vec![xr::sys::Vector3f::default(); vertex_count];
    let mut vertex_normals = vec![xr::sys::Vector3f::default(); vertex_count];
    let mut vertex_uvs = vec![xr::sys::Vector2f::default(); vertex_count];
    let mut vertex_blend_indices = vec![xr::sys::Vector4sFB::default(); vertex_count];
    let mut vertex_blend_weights = vec![xr::sys::Vector4f::default(); vertex_count];
    let mut indices = vec![0_i16; index_count];
    let mut mesh = xr::sys::HandTrackingMeshFB {
        ty: xr::sys::HandTrackingMeshFB::TYPE,
        next: ptr::null_mut(),
        joint_capacity_input: joint_count as u32,
        joint_count_output: 0,
        joint_bind_poses: joint_bind_poses.as_mut_ptr(),
        joint_radii: joint_radii.as_mut_ptr(),
        joint_parents: joint_parents.as_mut_ptr(),
        vertex_capacity_input: vertex_count as u32,
        vertex_count_output: 0,
        vertex_positions: vertex_positions.as_mut_ptr(),
        vertex_normals: vertex_normals.as_mut_ptr(),
        vertex_u_vs: vertex_uvs.as_mut_ptr(),
        vertex_blend_indices: vertex_blend_indices.as_mut_ptr(),
        vertex_blend_weights: vertex_blend_weights.as_mut_ptr(),
        index_capacity_input: index_count as u32,
        index_count_output: 0,
        indices: indices.as_mut_ptr(),
    };
    let result = unsafe { (mesh_ext.get_hand_mesh)(tracker.as_raw(), &mut mesh) };
    if result != xr::sys::Result::SUCCESS {
        return Err(format!(
            "{handedness} xrGetHandMeshFB populate failed: {result:?}"
        ));
    }
    joint_bind_poses.truncate(mesh.joint_count_output as usize);
    joint_radii.truncate(mesh.joint_count_output as usize);
    joint_parents.truncate(mesh.joint_count_output as usize);
    vertex_positions.truncate(mesh.vertex_count_output as usize);
    vertex_normals.truncate(mesh.vertex_count_output as usize);
    vertex_uvs.truncate(mesh.vertex_count_output as usize);
    vertex_blend_indices.truncate(mesh.vertex_count_output as usize);
    vertex_blend_weights.truncate(mesh.vertex_count_output as usize);
    indices.truncate(mesh.index_count_output as usize);
    let topology_key = format!(
        "openxr-fb-handmesh-v1-j{}-v{}-i{}",
        joint_bind_poses.len(),
        vertex_positions.len(),
        indices.len()
    );
    crate::marker(
        "hand-mesh-capture",
        format!(
            "status=mesh-loaded handedness={} topologyKey={} bindJointCount={} topologyVertexCount={} topologyIndexCount={} topologyTriangleCount={}",
            handedness,
            topology_key,
            joint_bind_poses.len(),
            vertex_positions.len(),
            indices.len(),
            indices.len() / 3
        ),
    );
    Ok(HandMeshRig {
        handedness,
        topology_key,
        joint_bind_poses,
        joint_radii,
        joint_parents,
        vertex_positions,
        vertex_normals,
        vertex_uvs,
        vertex_blend_indices,
        vertex_blend_weights,
        indices,
    })
}

fn empty_mesh_query() -> xr::sys::HandTrackingMeshFB {
    xr::sys::HandTrackingMeshFB {
        ty: xr::sys::HandTrackingMeshFB::TYPE,
        next: ptr::null_mut(),
        joint_capacity_input: 0,
        joint_count_output: 0,
        joint_bind_poses: ptr::null_mut(),
        joint_radii: ptr::null_mut(),
        joint_parents: ptr::null_mut(),
        vertex_capacity_input: 0,
        vertex_count_output: 0,
        vertex_positions: ptr::null_mut(),
        vertex_normals: ptr::null_mut(),
        vertex_u_vs: ptr::null_mut(),
        vertex_blend_indices: ptr::null_mut(),
        vertex_blend_weights: ptr::null_mut(),
        index_capacity_input: 0,
        index_count_output: 0,
        indices: ptr::null_mut(),
    }
}

struct LocatedMeshFrames {
    left: Option<LocatedHandMeshFrame>,
    right: Option<LocatedHandMeshFrame>,
}

struct LocatedHandMeshFrame {
    clip_row: serde_json::Value,
    validation_row: serde_json::Value,
}

fn locate_hand_mesh_frame(
    hand: &TrackedHandMesh,
    reference_space: &xr::Space,
    predicted_display_time: xr::Time,
    frame_index: u32,
    timestamp_ns: u64,
) -> Result<LocatedHandMeshFrame, String> {
    let locations = reference_space
        .locate_hand_joints(&hand.tracker, predicted_display_time)
        .map_err(|error| format!("locate_hand_joints failed: {error}"))?
        .ok_or_else(|| "inactive".to_string())?;
    let clip_row = compact_clip_row(hand.rig.handedness, frame_index, timestamp_ns, &locations)?;
    let (vertices, normals) = skin_validation_mesh(&hand.rig, &locations)?;
    let validation_row = json!({
        "schema": VALIDATION_ROW_SCHEMA,
        "handedness": hand.rig.handedness,
        "frame_index": frame_index,
        "timestamp_ns": timestamp_ns,
        "topology_key": hand.rig.topology_key,
        "vertices": vertices,
        "normals": normals,
    });
    Ok(LocatedHandMeshFrame {
        clip_row,
        validation_row,
    })
}

fn compact_clip_row(
    handedness: &'static str,
    frame_index: u32,
    timestamp_ns: u64,
    locations: &xr::HandJointLocations,
) -> Result<serde_json::Value, String> {
    let joints = RUNTIME_JOINTS
        .iter()
        .copied()
        .enumerate()
        .map(|(runtime_index, joint)| {
            let location = valid_location(locations, joint)?;
            Ok(json!({
                "joint_index": runtime_index,
                "openxr_joint_index": joint.into_raw(),
                "openxr_joint_name": openxr_joint_name(joint),
                "pose": {
                    "translation": [
                        location.pose.position.x,
                        location.pose.position.y,
                        location.pose.position.z,
                    ],
                    "rotation": [
                        location.pose.orientation.x,
                        location.pose.orientation.y,
                        location.pose.orientation.z,
                        location.pose.orientation.w,
                    ],
                },
                "radius_m": location.radius,
                "position_tracked": location
                    .location_flags
                    .contains(xr::SpaceLocationFlags::POSITION_TRACKED),
                "orientation_tracked": location
                    .location_flags
                    .contains(xr::SpaceLocationFlags::ORIENTATION_TRACKED),
            }))
        })
        .collect::<Result<Vec<_>, String>>()?;
    let tip_lengths = TIP_PAIRS
        .iter()
        .copied()
        .map(|(distal, tip)| {
            let distal = valid_location(locations, distal)?;
            let tip = valid_location(locations, tip)?;
            Ok(distance(distal.pose.position, tip.pose.position))
        })
        .collect::<Result<Vec<_>, String>>()?;
    Ok(json!({
        "schema": CLIP_ROW_SCHEMA,
        "handedness": handedness,
        "frame_index": frame_index,
        "timestamp_ns": timestamp_ns,
        "runtime_provider": "XR_EXT_hand_tracking",
        "mesh_provider": "XR_FB_hand_tracking_mesh",
        "reference_space": "openxr-local-space",
        "joints": joints,
        "tip_lengths_m": tip_lengths,
    }))
}

fn skin_validation_mesh(
    rig: &HandMeshRig,
    locations: &xr::HandJointLocations,
) -> Result<(Vec<[f32; 3]>, Vec<[f32; 3]>), String> {
    if rig.joint_bind_poses.len() > HAND_JOINT_COUNT {
        return Err("mesh rig has more bind joints than OpenXR default hand joints".to_string());
    }
    let runtime_poses = (0..rig.joint_bind_poses.len())
        .map(|joint_index| {
            let joint = xr::HandJoint::from_raw(joint_index as i32);
            valid_location(locations, joint).map(|location| location.pose)
        })
        .collect::<Result<Vec<_>, _>>()?;

    let vertices = rig
        .vertex_positions
        .iter()
        .enumerate()
        .map(|(index, position)| {
            let normal =
                rig.vertex_normals
                    .get(index)
                    .copied()
                    .unwrap_or_else(|| xr::sys::Vector3f {
                        x: 0.0,
                        y: 1.0,
                        z: 0.0,
                    });
            let (skinned, _) = skin_vertex_normal(
                *position,
                normal,
                blend_indices_to_array(rig.vertex_blend_indices[index]),
                blend_weights_to_array(rig.vertex_blend_weights[index]),
                &rig.joint_bind_poses,
                &runtime_poses,
            )?;
            Ok(skinned)
        })
        .collect::<Result<Vec<_>, String>>()?;
    let normals = rig
        .vertex_normals
        .iter()
        .enumerate()
        .map(|(index, normal)| {
            let (_, skinned_normal) = skin_vertex_normal(
                rig.vertex_positions[index],
                *normal,
                blend_indices_to_array(rig.vertex_blend_indices[index]),
                blend_weights_to_array(rig.vertex_blend_weights[index]),
                &rig.joint_bind_poses,
                &runtime_poses,
            )?;
            Ok(skinned_normal)
        })
        .collect::<Result<Vec<_>, String>>()?;
    Ok((vertices, normals))
}

fn skin_vertex_normal(
    bind_position: xr::sys::Vector3f,
    bind_normal: xr::sys::Vector3f,
    blend_indices: [i16; 4],
    blend_weights: [f32; 4],
    bind_poses: &[xr::sys::Posef],
    runtime_poses: &[xr::sys::Posef],
) -> Result<([f32; 3], [f32; 3]), String> {
    let mut skinned_position = [0.0_f32; 3];
    let mut skinned_normal = [0.0_f32; 3];
    let bind_position = [bind_position.x, bind_position.y, bind_position.z];
    let bind_normal = normalize3([bind_normal.x, bind_normal.y, bind_normal.z]);
    let mut total_weight = 0.0_f32;
    for (&joint_index, &weight) in blend_indices.iter().zip(blend_weights.iter()) {
        if weight <= 0.0 {
            continue;
        }
        let joint_index = usize::try_from(joint_index)
            .map_err(|_| "negative vertex blend joint index".to_string())?;
        let bind_pose = bind_poses
            .get(joint_index)
            .ok_or_else(|| "vertex blend joint index outside bind pose array".to_string())?;
        let runtime_pose = runtime_poses
            .get(joint_index)
            .ok_or_else(|| "vertex blend joint index outside runtime pose array".to_string())?;
        let local_position = inverse_transform_point(*bind_pose, bind_position);
        let local_normal = inverse_rotate(*bind_pose, bind_normal);
        let world_position = transform_point(*runtime_pose, local_position);
        let world_normal = rotate(*runtime_pose, local_normal);
        for axis in 0..3 {
            skinned_position[axis] += world_position[axis] * weight;
            skinned_normal[axis] += world_normal[axis] * weight;
        }
        total_weight += weight;
    }
    if total_weight <= 0.0 {
        return Err("vertex blend weights sum to zero".to_string());
    }
    if (total_weight - 1.0).abs() > 0.001 {
        for axis in 0..3 {
            skinned_position[axis] /= total_weight;
            skinned_normal[axis] /= total_weight;
        }
    }
    Ok((skinned_position, normalize3(skinned_normal)))
}

struct LiveHandMeshCaptureControl {
    enabled: bool,
    session_id: String,
    max_frames: u64,
    sample_period_frames: u64,
    validation_sample_period_frames: u64,
}

enum ControlReadError {
    Missing,
    Malformed(String),
}

fn read_control(path: &Path) -> Result<LiveHandMeshCaptureControl, ControlReadError> {
    if !path.exists() {
        return Err(ControlReadError::Missing);
    }
    let text = fs::read_to_string(path).map_err(|error| {
        ControlReadError::Malformed(format!("read control file failed: {error}"))
    })?;
    let value: serde_json::Value = serde_json::from_str(&text)
        .map_err(|error| ControlReadError::Malformed(format!("parse control JSON: {error}")))?;
    let schema = value
        .get("schema")
        .and_then(serde_json::Value::as_str)
        .unwrap_or_default();
    if schema != CONTROL_SCHEMA {
        return Err(ControlReadError::Malformed(format!(
            "unsupported control schema {schema}"
        )));
    }
    let enabled = value
        .get("enabled")
        .and_then(serde_json::Value::as_bool)
        .unwrap_or(false);
    let session_id = sanitize_session_id(
        value
            .get("session_id")
            .and_then(serde_json::Value::as_str)
            .unwrap_or("hand-mesh-live"),
    );
    let max_frames = value
        .get("max_frames")
        .and_then(serde_json::Value::as_u64)
        .unwrap_or(DEFAULT_MAX_FRAMES)
        .clamp(1, 36_000);
    let sample_period_frames = value
        .get("sample_period_frames")
        .and_then(serde_json::Value::as_u64)
        .unwrap_or(DEFAULT_SAMPLE_PERIOD_FRAMES)
        .clamp(1, 600);
    let validation_sample_period_frames = value
        .get("validation_sample_period_frames")
        .and_then(serde_json::Value::as_u64)
        .unwrap_or(DEFAULT_VALIDATION_SAMPLE_PERIOD_FRAMES)
        .clamp(1, 600);
    Ok(LiveHandMeshCaptureControl {
        enabled,
        session_id,
        max_frames,
        sample_period_frames,
        validation_sample_period_frames,
    })
}

struct LiveHandMeshCaptureSession {
    session_id: String,
    dir: PathBuf,
    left_clip: BufWriter<File>,
    right_clip: BufWriter<File>,
    left_validation: BufWriter<File>,
    right_validation: BufWriter<File>,
    status: BufWriter<File>,
    max_frames: u64,
    sample_period_frames: u64,
    validation_sample_period_frames: u64,
    started_unix_ms: u128,
    last_frame_count: u64,
    left_clip_frames: u64,
    right_clip_frames: u64,
    left_validation_frames: u64,
    right_validation_frames: u64,
    skipped_frames: u64,
    marker_period: u64,
    material_settings: HandMeshVisualMaterialSettings,
    finished: bool,
}

impl LiveHandMeshCaptureSession {
    fn start(
        capture_root: &Path,
        control: LiveHandMeshCaptureControl,
        source: &OpenXrHandMeshCaptureSource,
        material_settings: HandMeshVisualMaterialSettings,
    ) -> Result<Self, String> {
        let dir = capture_root.join(&control.session_id);
        fs::create_dir_all(&dir).map_err(|error| format!("create capture dir: {error}"))?;
        let left_clip = truncate_writer(&dir.join("left.clip.jsonl"))?;
        let right_clip = truncate_writer(&dir.join("right.clip.jsonl"))?;
        let left_validation = truncate_writer(&dir.join("left.validation_mesh.jsonl"))?;
        let right_validation = truncate_writer(&dir.join("right.validation_mesh.jsonl"))?;
        let status = truncate_writer(&dir.join("status.jsonl"))?;
        let session = Self {
            session_id: control.session_id,
            dir,
            left_clip,
            right_clip,
            left_validation,
            right_validation,
            status,
            max_frames: control.max_frames,
            sample_period_frames: control.sample_period_frames,
            validation_sample_period_frames: control.validation_sample_period_frames,
            started_unix_ms: unix_ms(),
            last_frame_count: 0,
            left_clip_frames: 0,
            right_clip_frames: 0,
            left_validation_frames: 0,
            right_validation_frames: 0,
            skipped_frames: 0,
            marker_period: 60,
            material_settings,
            finished: false,
        };
        if let Some(left) = source.left.as_ref() {
            session.write_rig(&left.rig)?;
        }
        if let Some(right) = source.right.as_ref() {
            session.write_rig(&right.rig)?;
        }
        session.write_manifest(None);
        crate::marker(
            "hand-mesh-capture",
            format!(
                "status=started captureId={} captureDir={} replayModes=recorded-mesh-validation-frames,recorded-joints-skin-live rigFiles=left.rig.json,right.rig.json clipFiles=left.clip.jsonl,right.clip.jsonl validationMeshFiles=left.validation_mesh.jsonl,right.validation_mesh.jsonl maxFrames={} samplePeriodFrames={} validationSamplePeriodFrames={} materialProfile={} materialAlpha={:.2} materialRimStrength={:.2} materialWireframeEnabled={} materialWireframeWidthPx={:.2}",
                crate::sanitize(&session.session_id),
                crate::sanitize(&path_marker(&session.dir)),
                session.max_frames,
                session.sample_period_frames,
                session.validation_sample_period_frames,
                session.material_settings.profile.marker_value(),
                session.material_settings.alpha,
                session.material_settings.rim_strength,
                session.material_settings.wireframe_enabled,
                session.material_settings.wireframe_width_px,
            ),
        );
        let mut session = session;
        session.write_status("started", "session-started");
        Ok(session)
    }

    fn record_frame(
        &mut self,
        source: &mut OpenXrHandMeshCaptureSource,
        reference_space: &xr::Space,
        predicted_display_time: xr::Time,
        renderer_frame: u64,
        material_settings: HandMeshVisualMaterialSettings,
    ) {
        if self.finished {
            return;
        }
        self.material_settings = material_settings;
        self.last_frame_count = renderer_frame;
        if renderer_frame % self.sample_period_frames != 0 {
            self.skipped_frames = self.skipped_frames.saturating_add(1);
            return;
        }
        let located = source.locate_frames(reference_space, predicted_display_time);
        let should_write_validation = renderer_frame % self.validation_sample_period_frames == 0;
        let mut wrote_any = false;
        if let Some(left) = located.left.as_ref() {
            if write_json_line(&mut self.left_clip, &left.clip_row).is_ok() {
                self.left_clip_frames = self.left_clip_frames.saturating_add(1);
                wrote_any = true;
            }
            if should_write_validation
                && write_json_line(&mut self.left_validation, &left.validation_row).is_ok()
            {
                self.left_validation_frames = self.left_validation_frames.saturating_add(1);
            }
        }
        if let Some(right) = located.right.as_ref() {
            if write_json_line(&mut self.right_clip, &right.clip_row).is_ok() {
                self.right_clip_frames = self.right_clip_frames.saturating_add(1);
                wrote_any = true;
            }
            if should_write_validation
                && write_json_line(&mut self.right_validation, &right.validation_row).is_ok()
            {
                self.right_validation_frames = self.right_validation_frames.saturating_add(1);
            }
        }
        if !wrote_any {
            self.skipped_frames = self.skipped_frames.saturating_add(1);
        }
        let total_clip = self.left_clip_frames.saturating_add(self.right_clip_frames);
        if total_clip == 1 || renderer_frame % self.marker_period == 0 {
            self.write_manifest(None);
            self.write_status("recording", "frame-sampled");
            crate::marker(
                "hand-mesh-capture",
                format!(
                    "status=recording captureId={} leftClipFrames={} rightClipFrames={} leftValidationFrames={} rightValidationFrames={} skippedFrames={} latestRendererFrame={} latestOpenXrTimeNs={} validationSamplePeriodFrames={} materialProfile={} captureDir={}",
                    crate::sanitize(&self.session_id),
                    self.left_clip_frames,
                    self.right_clip_frames,
                    self.left_validation_frames,
                    self.right_validation_frames,
                    self.skipped_frames,
                    renderer_frame,
                    predicted_display_time.as_nanos(),
                    self.validation_sample_period_frames,
                    self.material_settings.profile.marker_value(),
                    crate::sanitize(&path_marker(&self.dir)),
                ),
            );
        }
    }

    fn frame_limit_reached(&self) -> bool {
        self.left_clip_frames.saturating_add(self.right_clip_frames) >= self.max_frames
    }

    fn finish(&mut self, reason: &'static str) {
        if self.finished {
            return;
        }
        let _ = self.left_clip.flush();
        let _ = self.right_clip.flush();
        let _ = self.left_validation.flush();
        let _ = self.right_validation.flush();
        self.write_manifest(Some(reason));
        self.write_status("stopped", reason);
        let _ = self.status.flush();
        self.finished = true;
        crate::marker(
            "hand-mesh-capture",
            format!(
                "status=stopped reason={} captureId={} leftClipFrames={} rightClipFrames={} leftValidationFrames={} rightValidationFrames={} skippedFrames={} maxFrames={} captureDir={}",
                reason,
                crate::sanitize(&self.session_id),
                self.left_clip_frames,
                self.right_clip_frames,
                self.left_validation_frames,
                self.right_validation_frames,
                self.skipped_frames,
                self.max_frames,
                crate::sanitize(&path_marker(&self.dir)),
            ),
        );
    }

    fn write_rig(&self, rig: &HandMeshRig) -> Result<(), String> {
        fs::write(
            self.dir.join(format!("{}.rig.json", rig.handedness)),
            serde_json::to_string_pretty(&rig_json(rig)).map_err(|error| error.to_string())?,
        )
        .map_err(|error| format!("write {} rig: {error}", rig.handedness))
    }

    fn write_manifest(&self, finished_reason: Option<&'static str>) {
        let manifest = json!({
            "schema": MANIFEST_SCHEMA,
            "capture_id": self.session_id,
            "provider": "rusty-quest-native-openxr-xr-fb-hand-tracking-mesh",
            "source_kind": "native-openxr-xr-fb-hand-tracking-mesh",
            "recorded_input_equivalent": true,
            "reference_space": "openxr-local-space",
            "coordinate_system": "openxr-local-right-handed-meters",
            "required_extensions": [
                "XR_EXT_hand_tracking",
                "XR_FB_hand_tracking_mesh",
            ],
            "runtime_provider": "XR_EXT_hand_tracking",
            "mesh_provider": "XR_FB_hand_tracking_mesh",
            "rig_schema": RIG_SCHEMA,
            "clip_row_schema": CLIP_ROW_SCHEMA,
            "validation_row_schema": VALIDATION_ROW_SCHEMA,
            "control_schema": CONTROL_SCHEMA,
            "artifact_files": {
                "left_rig": "left.rig.json",
                "right_rig": "right.rig.json",
                "left_clip": "left.clip.jsonl",
                "right_clip": "right.clip.jsonl",
                "left_validation_mesh": "left.validation_mesh.jsonl",
                "right_validation_mesh": "right.validation_mesh.jsonl",
                "status": "status.jsonl"
            },
            "runtime_joint_count": RUNTIME_JOINTS.len(),
            "tip_length_count": TIP_LENGTH_COUNT,
            "left_clip_frame_count": self.left_clip_frames,
            "right_clip_frame_count": self.right_clip_frames,
            "left_validation_frame_count": self.left_validation_frames,
            "right_validation_frame_count": self.right_validation_frames,
            "skipped_frame_count": self.skipped_frames,
            "max_frames": self.max_frames,
            "sample_period_frames": self.sample_period_frames,
            "validation_sample_period_frames": self.validation_sample_period_frames,
            "started_unix_ms": self.started_unix_ms,
            "finished_unix_ms": finished_reason.map(|_| unix_ms()),
            "finished_reason": finished_reason.unwrap_or("active"),
            "last_renderer_frame": self.last_frame_count,
            "hand_material": {
                "profile": self.material_settings.profile.marker_value(),
                "base_color": self.material_settings.base_color,
                "alpha": self.material_settings.alpha,
                "rim_strength": self.material_settings.rim_strength,
                "wireframe_enabled": self.material_settings.wireframe_enabled,
                "wireframe_width_px": self.material_settings.wireframe_width_px,
                "source": "procedural-reference-not-unity-asset"
            }
        });
        let _ = fs::write(
            self.dir.join("capture.manifest.json"),
            serde_json::to_string_pretty(&manifest).unwrap_or_else(|_| "{}".to_string()),
        );
    }

    fn write_status(&mut self, status: &'static str, reason: &'static str) {
        let row = json!({
            "schema": STATUS_ROW_SCHEMA,
            "capture_id": self.session_id,
            "status": status,
            "reason": reason,
            "unix_ms": unix_ms(),
            "left_clip_frames": self.left_clip_frames,
            "right_clip_frames": self.right_clip_frames,
            "left_validation_frames": self.left_validation_frames,
            "right_validation_frames": self.right_validation_frames,
            "skipped_frames": self.skipped_frames,
            "latest_renderer_frame": self.last_frame_count,
        });
        let _ = write_json_line(&mut self.status, &row);
    }
}

fn rig_json(rig: &HandMeshRig) -> serde_json::Value {
    let joints = rig
        .joint_bind_poses
        .iter()
        .enumerate()
        .map(|(index, pose)| {
            let parent = rig.joint_parents.get(index).copied();
            let parent_index = parent
                .map(|joint| joint.into_raw())
                .filter(|raw| *raw >= 0 && *raw as usize != index);
            json!({
                "index": index,
                "name": openxr_joint_name(xr::HandJoint::from_raw(index as i32)),
                "parent_index": parent_index,
                "radius_m": rig.joint_radii.get(index).copied().unwrap_or(0.0),
                "bind_pose": pose_json(*pose),
            })
        })
        .collect::<Vec<_>>();
    let triangles = rig
        .indices
        .chunks(3)
        .filter(|chunk| chunk.len() == 3)
        .map(|chunk| [chunk[0], chunk[1], chunk[2]])
        .collect::<Vec<_>>();
    json!({
        "schema": RIG_SCHEMA,
        "handedness": rig.handedness,
        "reference_space": "openxr-local-space",
        "topology_key": rig.topology_key,
        "bind_version": "XR_FB_hand_tracking_mesh",
        "runtime_joint_set": runtime_joint_set_json(),
        "joints": joints,
        "bind_vertices": rig.vertex_positions.iter().map(|v| [v.x, v.y, v.z]).collect::<Vec<_>>(),
        "bind_normals": rig.vertex_normals.iter().map(|v| [v.x, v.y, v.z]).collect::<Vec<_>>(),
        "triangle_indices": triangles,
        "vertex_uvs": rig.vertex_uvs.iter().map(|uv| [uv.x, uv.y]).collect::<Vec<_>>(),
        "vertex_blend_indices": rig.vertex_blend_indices.iter().map(|v| [v.x, v.y, v.z, v.w]).collect::<Vec<_>>(),
        "vertex_blend_weights": rig.vertex_blend_weights.iter().map(|v| [v.x, v.y, v.z, v.w]).collect::<Vec<_>>(),
    })
}

fn runtime_joint_set_json() -> serde_json::Value {
    let bind_joint_sources = (0..HAND_JOINT_COUNT)
        .map(|bind_joint_index| {
            if let Some(runtime_joint_index) = runtime_joint_index_for_bind_joint(bind_joint_index)
            {
                json!({
                    "bind_joint_index": bind_joint_index,
                    "source_kind": "runtime_pose",
                    "runtime_joint_index": runtime_joint_index,
                    "openxr_joint_index": bind_joint_index,
                })
            } else {
                let (tip_length_index, parent_runtime_joint_index) =
                    tip_source_for_bind_joint(bind_joint_index).unwrap_or((0, 4));
                json!({
                    "bind_joint_index": bind_joint_index,
                    "source_kind": "tip_length_from_parent_pose",
                    "tip_length_index": tip_length_index,
                    "parent_runtime_joint_index": parent_runtime_joint_index,
                    "openxr_joint_index": bind_joint_index,
                })
            }
        })
        .collect::<Vec<_>>();
    json!({
        "provider": "XR_EXT_hand_tracking",
        "joint_count": RUNTIME_JOINTS.len(),
        "joint_names": RUNTIME_JOINTS
            .iter()
            .copied()
            .map(openxr_joint_name)
            .collect::<Vec<_>>(),
        "tip_length_count": TIP_LENGTH_COUNT,
        "bind_joint_sources": bind_joint_sources,
    })
}

fn runtime_joint_index_for_bind_joint(bind_joint_index: usize) -> Option<usize> {
    RUNTIME_JOINTS
        .iter()
        .position(|joint| joint.into_raw() as usize == bind_joint_index)
}

fn tip_source_for_bind_joint(bind_joint_index: usize) -> Option<(usize, usize)> {
    TIP_PAIRS
        .iter()
        .enumerate()
        .find(|(_, (_, tip))| tip.into_raw() as usize == bind_joint_index)
        .and_then(|(tip_length_index, (parent, _))| {
            runtime_joint_index_for_bind_joint(parent.into_raw() as usize)
                .map(|parent_runtime_joint_index| (tip_length_index, parent_runtime_joint_index))
        })
}

fn valid_location(
    locations: &xr::HandJointLocations,
    joint: xr::HandJoint,
) -> Result<&xr::HandJointLocation, String> {
    let location = &locations[joint];
    if location.location_flags.contains(
        xr::SpaceLocationFlags::POSITION_VALID | xr::SpaceLocationFlags::ORIENTATION_VALID,
    ) {
        Ok(location)
    } else {
        Err(format!(
            "joint-location-invalid-{}",
            openxr_joint_name(joint)
        ))
    }
}

fn pose_json(pose: xr::sys::Posef) -> serde_json::Value {
    json!({
        "translation": [pose.position.x, pose.position.y, pose.position.z],
        "rotation": [
            pose.orientation.x,
            pose.orientation.y,
            pose.orientation.z,
            pose.orientation.w,
        ],
    })
}

fn openxr_joint_name(joint: xr::HandJoint) -> &'static str {
    OPENXR_JOINT_NAMES
        .get(joint.into_raw() as usize)
        .copied()
        .unwrap_or("unknown_ext")
}

fn blend_indices_to_array(value: xr::sys::Vector4sFB) -> [i16; 4] {
    [value.x, value.y, value.z, value.w]
}

fn blend_weights_to_array(value: xr::sys::Vector4f) -> [f32; 4] {
    [value.x, value.y, value.z, value.w]
}

fn distance(left: xr::Vector3f, right: xr::Vector3f) -> f32 {
    let dx = left.x - right.x;
    let dy = left.y - right.y;
    let dz = left.z - right.z;
    (dx * dx + dy * dy + dz * dz).sqrt()
}

fn transform_point(pose: xr::sys::Posef, point: [f32; 3]) -> [f32; 3] {
    let rotated = rotate(pose, point);
    [
        rotated[0] + pose.position.x,
        rotated[1] + pose.position.y,
        rotated[2] + pose.position.z,
    ]
}

fn inverse_transform_point(pose: xr::sys::Posef, point: [f32; 3]) -> [f32; 3] {
    inverse_rotate(
        pose,
        [
            point[0] - pose.position.x,
            point[1] - pose.position.y,
            point[2] - pose.position.z,
        ],
    )
}

fn rotate(pose: xr::sys::Posef, vector: [f32; 3]) -> [f32; 3] {
    rotate_quat(
        [
            pose.orientation.x,
            pose.orientation.y,
            pose.orientation.z,
            pose.orientation.w,
        ],
        vector,
    )
}

fn inverse_rotate(pose: xr::sys::Posef, vector: [f32; 3]) -> [f32; 3] {
    rotate_quat(
        [
            -pose.orientation.x,
            -pose.orientation.y,
            -pose.orientation.z,
            pose.orientation.w,
        ],
        vector,
    )
}

fn rotate_quat(q: [f32; 4], v: [f32; 3]) -> [f32; 3] {
    let qv = [q[0], q[1], q[2]];
    let uv = cross(qv, v);
    let uuv = cross(qv, uv);
    [
        v[0] + (uv[0] * q[3] + uuv[0]) * 2.0,
        v[1] + (uv[1] * q[3] + uuv[1]) * 2.0,
        v[2] + (uv[2] * q[3] + uuv[2]) * 2.0,
    ]
}

fn cross(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}

fn normalize3(value: [f32; 3]) -> [f32; 3] {
    let length = (value[0] * value[0] + value[1] * value[1] + value[2] * value[2]).sqrt();
    if length <= 1.0e-8 {
        [0.0, 1.0, 0.0]
    } else {
        [value[0] / length, value[1] / length, value[2] / length]
    }
}

fn truncate_writer(path: &Path) -> Result<BufWriter<File>, String> {
    let file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(path)
        .map_err(|error| format!("open {}: {error}", path_marker(path)))?;
    Ok(BufWriter::new(file))
}

fn write_json_line(writer: &mut BufWriter<File>, row: &serde_json::Value) -> Result<(), String> {
    writer
        .write_all(row.to_string().as_bytes())
        .map_err(|error| format!("write JSON row: {error}"))?;
    writer
        .write_all(b"\n")
        .map_err(|error| format!("write JSON newline: {error}"))?;
    writer
        .flush()
        .map_err(|error| format!("flush JSON row: {error}"))
}

fn sanitize_session_id(value: &str) -> String {
    let mut sanitized = value
        .chars()
        .filter_map(|ch| {
            if ch.is_ascii_alphanumeric() || matches!(ch, '-' | '_' | '.') {
                Some(ch)
            } else if ch.is_whitespace() {
                Some('-')
            } else {
                None
            }
        })
        .take(96)
        .collect::<String>();
    if sanitized.is_empty() {
        sanitized = "hand-mesh-live".to_string();
    }
    sanitized
}

fn unix_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .unwrap_or(0)
}

fn path_marker(path: &Path) -> String {
    path.to_string_lossy().replace('\\', "/")
}
