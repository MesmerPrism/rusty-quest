#[cfg(target_os = "android")]
use std::ffi::CStr;
use std::ffi::CString;
use std::mem;
#[cfg(target_os = "android")]
use std::os::raw::c_char;
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Mutex, OnceLock};

use openxr_sys::Handle;

use crate::{android_log_info, bool_token, marker_token};

pub(crate) const LIVE_HAND_COUNT: usize = 2;
pub(crate) const LIVE_HAND_JOINT_COUNT: usize = openxr_sys::HAND_JOINT_COUNT_EXT;
pub(crate) const LIVE_HAND_ROW_COUNT: usize = LIVE_HAND_COUNT * LIVE_HAND_JOINT_COUNT;

const LIVE_HAND_MIN_JOINT_RADIUS_METERS: f32 = 0.0075;
const LIVE_HAND_COMPACT_RUNTIME_OPENXR_JOINTS: [usize; 21] = [
    0, 1, 2, 3, 4, 6, 7, 8, 9, 11, 12, 13, 14, 16, 17, 18, 19, 21, 22, 23, 24,
];
const LIVE_HAND_COMPACT_TIP_OPENXR_PAIRS: [(usize, usize); 5] =
    [(4, 5), (9, 10), (14, 15), (19, 20), (24, 25)];
const CLOCK_MONOTONIC: libc::c_int = 1;
const LIVE_HAND_SCENE_OFFSET_X_PROPERTY: &str =
    "debug.rustyquest.kuramoto_spatial.live_hand_scene.offset_x_m";
const LIVE_HAND_SCENE_OFFSET_Y_PROPERTY: &str =
    "debug.rustyquest.kuramoto_spatial.live_hand_scene.offset_y_m";
const LIVE_HAND_SCENE_OFFSET_Z_PROPERTY: &str =
    "debug.rustyquest.kuramoto_spatial.live_hand_scene.offset_z_m";
const LIVE_HAND_SCENE_YAW_DEGREES_PROPERTY: &str =
    "debug.rustyquest.kuramoto_spatial.live_hand_scene.yaw_degrees";
const LIVE_HAND_SCENE_HORIZONTAL_SIGN_PROPERTY: &str =
    "debug.rustyquest.kuramoto_spatial.live_hand_scene.horizontal_sign";
const LIVE_HAND_SPATIAL_VIEWER_WORLD_REGISTRATION_MODE_PROPERTY: &str =
    "debug.rustyquest.kuramoto_spatial.live_hand_spatial_viewer_world_registration.mode";
const LIVE_HAND_SPATIAL_VIEWER_WORLD_REGISTRATION_PARITY_PROPERTY: &str =
    "debug.rustyquest.kuramoto_spatial.live_hand_spatial_viewer_world_registration.parity";
const LIVE_HAND_SPATIAL_VIEWER_WORLD_REFLECTION_ORIENTATION_PROPERTY: &str =
    "debug.rustyquest.kuramoto_spatial.live_hand_spatial_viewer_world_registration.reflection_orientation";

static LIVE_HAND_SCENE_OFFSET_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_SCENE_OFFSET_Y_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_SCENE_OFFSET_Z_BITS: AtomicU32 = AtomicU32::new(2.0_f32.to_bits());
static LIVE_HAND_SCENE_YAW_DEGREES_BITS: AtomicU32 = AtomicU32::new(180.0_f32.to_bits());
static LIVE_HAND_SCENE_HORIZONTAL_SIGN_BITS: AtomicU32 = AtomicU32::new((-1.0_f32).to_bits());
static LIVE_HAND_SCENE_TRANSFORM_LOG_COUNT: AtomicU32 = AtomicU32::new(0);
static LIVE_HAND_PANEL_CENTER_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_PANEL_CENTER_Y_BITS: AtomicU32 = AtomicU32::new(1.22_f32.to_bits());
static LIVE_HAND_PANEL_CENTER_Z_BITS: AtomicU32 = AtomicU32::new((-0.72_f32).to_bits());
static LIVE_HAND_PANEL_RIGHT_X_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static LIVE_HAND_PANEL_RIGHT_Y_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_PANEL_RIGHT_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_PANEL_UP_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_PANEL_UP_Y_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static LIVE_HAND_PANEL_UP_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_PANEL_FORWARD_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_PANEL_FORWARD_Y_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_PANEL_FORWARD_Z_BITS: AtomicU32 = AtomicU32::new((-1.0_f32).to_bits());
static LIVE_HAND_PANEL_TARGET_DISTANCE_BITS: AtomicU32 = AtomicU32::new(0.72_f32.to_bits());
static LIVE_HAND_PANEL_BASIS_VALID: AtomicBool = AtomicBool::new(false);
static LIVE_HAND_SPATIAL_VIEWER_WORLD_CENTER_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_SPATIAL_VIEWER_WORLD_CENTER_Y_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_SPATIAL_VIEWER_WORLD_CENTER_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_SPATIAL_VIEWER_WORLD_RIGHT_X_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static LIVE_HAND_SPATIAL_VIEWER_WORLD_RIGHT_Y_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_SPATIAL_VIEWER_WORLD_RIGHT_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_SPATIAL_VIEWER_WORLD_UP_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_SPATIAL_VIEWER_WORLD_UP_Y_BITS: AtomicU32 = AtomicU32::new(1.0_f32.to_bits());
static LIVE_HAND_SPATIAL_VIEWER_WORLD_UP_Z_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_SPATIAL_VIEWER_WORLD_FORWARD_X_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_SPATIAL_VIEWER_WORLD_FORWARD_Y_BITS: AtomicU32 = AtomicU32::new(0.0_f32.to_bits());
static LIVE_HAND_SPATIAL_VIEWER_WORLD_FORWARD_Z_BITS: AtomicU32 =
    AtomicU32::new((-1.0_f32).to_bits());
static LIVE_HAND_SPATIAL_VIEWER_WORLD_BASIS_VALID: AtomicBool = AtomicBool::new(false);
static LIVE_HAND_SPATIAL_VIEWER_WORLD_REGISTRATION: OnceLock<
    Mutex<Option<LiveHandSpatialViewerWorldMapping>>,
> = OnceLock::new();
static LIVE_HAND_SPATIAL_VIEWER_WORLD_PARITY_BITS: AtomicU32 = AtomicU32::new(0);
static LIVE_HAND_SPATIAL_VIEWER_WORLD_PARITY_LOG_COUNT: AtomicU32 = AtomicU32::new(0);
static LIVE_HAND_SPATIAL_VIEWER_WORLD_REFLECTION_ORIENTATION_BITS: AtomicU32 = AtomicU32::new(0);
static LIVE_HAND_LAST_ROWS: OnceLock<Mutex<Option<LiveHandRowsSnapshot>>> = OnceLock::new();
static LIVE_HAND_LAST_RAW_SCENE_ROWS: OnceLock<Mutex<Option<LiveHandRowsSnapshot>>> =
    OnceLock::new();
static LIVE_HAND_LAST_SPATIAL_VIEWER_WORLD_ROWS: OnceLock<Mutex<Option<LiveHandRowsSnapshot>>> =
    OnceLock::new();

extern "C" {
    fn clock_gettime(clock_id: libc::c_int, time_spec: *mut libc::timespec) -> libc::c_int;
}

#[cfg(target_os = "android")]
extern "C" {
    fn __system_property_get(name: *const c_char, value: *mut c_char) -> libc::c_int;
}

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct LiveHandOpenXrHandles {
    pub(crate) instance_handle: i64,
    pub(crate) session_handle: i64,
    pub(crate) get_instance_proc_addr_handle: i64,
}

impl LiveHandOpenXrHandles {
    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "liveHandOpenXrInstanceHandleNonZero={} liveHandOpenXrSessionHandleNonZero={} liveHandOpenXrGetInstanceProcAddrHandleNonZero={}",
            bool_token(self.instance_handle != 0),
            bool_token(self.session_handle != 0),
            bool_token(self.get_instance_proc_addr_handle != 0),
        )
    }

    fn is_complete(self) -> bool {
        self.instance_handle != 0
            && self.session_handle != 0
            && self.get_instance_proc_addr_handle != 0
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct LiveHandJointRow {
    pub(crate) position_radius: [f32; 4],
    pub(crate) status: [f32; 4],
    pub(crate) orientation_xyzw: [f32; 4],
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct LiveHandRowsSnapshot {
    pub(crate) rows: [LiveHandJointRow; LIVE_HAND_ROW_COUNT],
    pub(crate) active_hand_count: u32,
    pub(crate) frame_index: u32,
    pub(crate) timestamp_ns: i64,
}

impl Default for LiveHandJointRow {
    fn default() -> Self {
        Self {
            position_radius: [0.0, 0.0, 0.0, LIVE_HAND_MIN_JOINT_RADIUS_METERS],
            status: [0.0, 0.0, 0.0, 0.0],
            orientation_xyzw: [0.0, 0.0, 0.0, 1.0],
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct LiveHandJointStatus {
    pub(crate) input_ready: bool,
    pub(crate) frame_ready: bool,
    pub(crate) extension_functions_resolved: bool,
    pub(crate) timespec_converter_resolved: bool,
    pub(crate) system_supported: bool,
    pub(crate) reference_space_ready: bool,
    pub(crate) tracker_ready: bool,
    pub(crate) left_active: bool,
    pub(crate) right_active: bool,
    pub(crate) active_hand_count: u32,
    pub(crate) visualizable_joint_count: u32,
    pub(crate) compact_runtime_joint_pose_count: u32,
    pub(crate) compact_tip_length_count: u32,
    pub(crate) frame_index: u32,
    pub(crate) timestamp_ns: i64,
    pub(crate) view_pose_ready: bool,
    pub(crate) view_locate_status: String,
    pub(crate) coordinate_mapping: &'static str,
    pub(crate) left_locate_status: String,
    pub(crate) right_locate_status: String,
    pub(crate) fallback_reason: String,
    pub(crate) time_source: &'static str,
    pub(crate) reference_space_type: &'static str,
}

impl Default for LiveHandJointStatus {
    fn default() -> Self {
        Self {
            input_ready: false,
            frame_ready: false,
            extension_functions_resolved: false,
            timespec_converter_resolved: false,
            system_supported: false,
            reference_space_ready: false,
            tracker_ready: false,
            left_active: false,
            right_active: false,
            active_hand_count: 0,
            visualizable_joint_count: 0,
            compact_runtime_joint_pose_count: 0,
            compact_tip_length_count: 0,
            frame_index: 0,
            timestamp_ns: 0,
            view_pose_ready: false,
            view_locate_status: "not-requested".to_string(),
            coordinate_mapping: "unavailable",
            left_locate_status: "not-requested".to_string(),
            right_locate_status: "not-requested".to_string(),
            fallback_reason: "unavailable".to_string(),
            time_source: "unavailable",
            reference_space_type: "unavailable",
        }
    }
}

impl LiveHandJointStatus {
    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "liveHandJointInputReady={} liveHandJointFrameReady={} liveHandJointFrameSource=XR_EXT_hand_tracking liveHandJointBufferPath=host-visible-storage-buffer liveHandJointGpuInputPath=recorded-compatible-compact-joint-pose-gpu-skinning liveHandCompactUploadEquivalent=true liveHandCompactFrameGate=native-equivalent-21-runtime-5-tip liveHandRuntimeJointPoseCount={} liveHandTipLengthCount={} liveHandJointPlacementMode=viewer-relative-openxr-to-spatial-sdk-panel-plane liveHandCoordinateTransform={} liveHandSceneTransformSource=runtime-hotload-android-property liveHandSceneOffsetProperties={};{};{} liveHandSceneYawProperty={} liveHandSceneHorizontalSignProperty={} liveHandSceneOffsetDefaultM=0.000;0.000;2.000 liveHandSceneYawDefaultDegrees=180.000 liveHandSceneHorizontalSignDefault=-1.000 liveHandViewPoseSource=xrLocateViews liveHandPanelBasisSource=Scene.getViewerPose-panel-plane liveHandCorrectPositionSizeProof=spatial-sdk-panel-plane-projection liveHandReferenceSpaceType={} liveHandPoseOrientationUpload=true liveHandJointStatusX=position-valid liveHandJointStatusY=pose-valid liveHandJointStatusW=position-tracked liveHandSkinningValidityPolicy=native-compact-frame-gate-trust-all-weights liveHandTimeSource={} liveHandTimespecConverterResolved={} liveHandExtensionFunctionsResolved={} liveHandTrackingSystemSupported={} liveHandReferenceSpaceReady={} liveHandTrackerReady={} liveHandViewPoseReady={} liveHandViewLocateStatus={} liveHandLeftActive={} liveHandRightActive={} liveHandUsingBoth={} liveHandActiveHandCount={} liveHandVisualizableJointCount={} liveHandFrameIndex={} liveHandTimestampNs={} liveHandLeftLocateStatus={} liveHandRightLocateStatus={} liveHandFallbackToReplay={} liveHandFallbackReason={}",
            bool_token(self.input_ready),
            bool_token(self.frame_ready),
            self.compact_runtime_joint_pose_count,
            self.compact_tip_length_count,
            self.coordinate_mapping,
            LIVE_HAND_SCENE_OFFSET_X_PROPERTY,
            LIVE_HAND_SCENE_OFFSET_Y_PROPERTY,
            LIVE_HAND_SCENE_OFFSET_Z_PROPERTY,
            LIVE_HAND_SCENE_YAW_DEGREES_PROPERTY,
            LIVE_HAND_SCENE_HORIZONTAL_SIGN_PROPERTY,
            self.reference_space_type,
            self.time_source,
            bool_token(self.timespec_converter_resolved),
            bool_token(self.extension_functions_resolved),
            bool_token(self.system_supported),
            bool_token(self.reference_space_ready),
            bool_token(self.tracker_ready),
            bool_token(self.view_pose_ready),
            marker_token(&self.view_locate_status),
            bool_token(self.left_active),
            bool_token(self.right_active),
            bool_token(self.left_active && self.right_active),
            self.active_hand_count,
            self.visualizable_joint_count,
            self.frame_index,
            self.timestamp_ns,
            marker_token(&self.left_locate_status),
            marker_token(&self.right_locate_status),
            bool_token(!self.frame_ready),
            marker_token(&self.fallback_reason),
        )
    }
}

struct ResolvedOpenXrFunction {
    resolved: bool,
    result: String,
    function: Option<openxr_sys::pfn::VoidFunction>,
}

pub(crate) struct LiveHandJointInput {
    handles: LiveHandOpenXrHandles,
    instance: openxr_sys::Instance,
    session: openxr_sys::Session,
    get_system_properties: Option<openxr_sys::pfn::GetSystemProperties>,
    destroy_space: Option<openxr_sys::pfn::DestroySpace>,
    locate_views: Option<openxr_sys::pfn::LocateViews>,
    destroy_hand_tracker: Option<openxr_sys::pfn::DestroyHandTrackerEXT>,
    locate_hand_joints: Option<openxr_sys::pfn::LocateHandJointsEXT>,
    convert_timespec_time_to_time: Option<openxr_sys::pfn::ConvertTimespecTimeToTimeKHR>,
    local_space: openxr_sys::Space,
    trackers: [Option<openxr_sys::HandTrackerEXT>; LIVE_HAND_COUNT],
    frame_counter: u32,
    spatial_viewer_world_registration_log_count: u32,
    status: LiveHandJointStatus,
    status_log_count: u32,
    first_frame_logged: bool,
}

impl LiveHandJointInput {
    pub(crate) fn new(handles: LiveHandOpenXrHandles) -> Self {
        let mut input = Self::unavailable(handles, "not-initialized");
        if !handles.is_complete() {
            input.status.fallback_reason = "missing-openxr-handles".to_string();
            input.log_status("live-hand-joints-unavailable");
            return input;
        }

        let instance = openxr_sys::Instance::from_raw(handles.instance_handle as u64);
        let session = openxr_sys::Session::from_raw(handles.session_handle as u64);
        let get_instance_proc_addr: openxr_sys::pfn::GetInstanceProcAddr =
            unsafe { mem::transmute(handles.get_instance_proc_addr_handle as usize) };

        let get_system = resolve_openxr_function(instance, get_instance_proc_addr, "xrGetSystem");
        let get_system_properties =
            resolve_openxr_function(instance, get_instance_proc_addr, "xrGetSystemProperties");
        let create_reference_space =
            resolve_openxr_function(instance, get_instance_proc_addr, "xrCreateReferenceSpace");
        let destroy_space =
            resolve_openxr_function(instance, get_instance_proc_addr, "xrDestroySpace");
        let locate_views =
            resolve_openxr_function(instance, get_instance_proc_addr, "xrLocateViews");
        let create_hand_tracker =
            resolve_openxr_function(instance, get_instance_proc_addr, "xrCreateHandTrackerEXT");
        let destroy_hand_tracker =
            resolve_openxr_function(instance, get_instance_proc_addr, "xrDestroyHandTrackerEXT");
        let locate_hand_joints =
            resolve_openxr_function(instance, get_instance_proc_addr, "xrLocateHandJointsEXT");
        let convert_timespec_time_to_time = resolve_openxr_function(
            instance,
            get_instance_proc_addr,
            "xrConvertTimespecTimeToTimeKHR",
        );

        let extension_functions_resolved = create_hand_tracker.resolved
            && destroy_hand_tracker.resolved
            && locate_hand_joints.resolved;

        let mut input = Self {
            handles,
            instance,
            session,
            get_system_properties: typed_function(get_system_properties.function),
            destroy_space: typed_function(destroy_space.function),
            locate_views: typed_function(locate_views.function),
            destroy_hand_tracker: typed_function(destroy_hand_tracker.function),
            locate_hand_joints: typed_function(locate_hand_joints.function),
            convert_timespec_time_to_time: typed_function(convert_timespec_time_to_time.function),
            local_space: openxr_sys::Space::NULL,
            trackers: [None, None],
            frame_counter: 0,
            spatial_viewer_world_registration_log_count: 0,
            status: LiveHandJointStatus {
                extension_functions_resolved,
                view_locate_status: if locate_views.resolved {
                    "pending".to_string()
                } else {
                    format!("xrLocateViews-{}", locate_views.result)
                },
                timespec_converter_resolved: convert_timespec_time_to_time.resolved,
                fallback_reason: "initializing".to_string(),
                ..LiveHandJointStatus::default()
            },
            status_log_count: 0,
            first_frame_logged: false,
        };

        if !get_system.resolved {
            input.status.fallback_reason = format!("xrGetSystem-{}", get_system.result);
            input.log_status("live-hand-joints-unavailable");
            return input;
        }
        let get_system_function: openxr_sys::pfn::GetSystem =
            unsafe { mem::transmute(get_system.function.expect("resolved function")) };
        let get_info = openxr_sys::SystemGetInfo {
            ty: openxr_sys::SystemGetInfo::TYPE,
            next: ptr::null(),
            form_factor: openxr_sys::FormFactor::HEAD_MOUNTED_DISPLAY,
        };
        let mut system_id = openxr_sys::SystemId::NULL;
        let get_system_result = unsafe { get_system_function(instance, &get_info, &mut system_id) };
        if get_system_result != openxr_sys::Result::SUCCESS {
            input.status.fallback_reason =
                format!("xrGetSystem-{}", xr_result_token(get_system_result));
            input.log_status("live-hand-joints-unavailable");
            return input;
        }

        input.status.system_supported = input.query_system_hand_support(system_id);
        if !input.status.system_supported {
            input.status.fallback_reason = "system-hand-tracking-not-supported".to_string();
            input.log_status("live-hand-joints-unavailable");
            return input;
        }
        if !extension_functions_resolved {
            input.status.fallback_reason = format!(
                "hand-functions-create_{}_destroy_{}_locate_{}",
                create_hand_tracker.result, destroy_hand_tracker.result, locate_hand_joints.result
            );
            input.log_status("live-hand-joints-unavailable");
            return input;
        }
        if !create_reference_space.resolved || !destroy_space.resolved {
            input.status.fallback_reason = format!(
                "reference-space-functions-create_{}_destroy_{}",
                create_reference_space.result, destroy_space.result
            );
            input.log_status("live-hand-joints-unavailable");
            return input;
        }

        let create_reference_space_function: openxr_sys::pfn::CreateReferenceSpace =
            unsafe { mem::transmute(create_reference_space.function.expect("resolved function")) };
        input.local_space =
            match create_hand_reference_space(session, create_reference_space_function) {
                Ok((space, reference_space_type)) => {
                    input.status.reference_space_type = reference_space_type;
                    space
                }
                Err(error) => {
                    input.status.fallback_reason = error;
                    input.log_status("live-hand-joints-unavailable");
                    return input;
                }
            };
        input.status.reference_space_ready = true;

        let create_hand_tracker_function: openxr_sys::pfn::CreateHandTrackerEXT =
            unsafe { mem::transmute(create_hand_tracker.function.expect("resolved function")) };
        input.trackers[0] = input.create_tracker(
            create_hand_tracker_function,
            openxr_sys::HandEXT::LEFT,
            "left",
        );
        input.trackers[1] = input.create_tracker(
            create_hand_tracker_function,
            openxr_sys::HandEXT::RIGHT,
            "right",
        );
        input.status.tracker_ready = input.trackers.iter().any(Option::is_some);
        input.status.input_ready = input.status.tracker_ready;
        input.status.fallback_reason = if input.status.tracker_ready {
            "waiting-for-active-hand-frame".to_string()
        } else {
            "tracker-create-failed".to_string()
        };
        input.log_status(if input.status.tracker_ready {
            "live-hand-joints-created"
        } else {
            "live-hand-joints-unavailable"
        });
        input
    }

    fn unavailable(handles: LiveHandOpenXrHandles, reason: &'static str) -> Self {
        Self {
            handles,
            instance: openxr_sys::Instance::NULL,
            session: openxr_sys::Session::NULL,
            get_system_properties: None,
            destroy_space: None,
            locate_views: None,
            destroy_hand_tracker: None,
            locate_hand_joints: None,
            convert_timespec_time_to_time: None,
            local_space: openxr_sys::Space::NULL,
            trackers: [None, None],
            frame_counter: 0,
            spatial_viewer_world_registration_log_count: 0,
            status: LiveHandJointStatus {
                fallback_reason: reason.to_string(),
                ..LiveHandJointStatus::default()
            },
            status_log_count: 0,
            first_frame_logged: false,
        }
    }

    pub(crate) fn status(&self) -> &LiveHandJointStatus {
        &self.status
    }

    pub(crate) fn update_rows(&mut self) -> [LiveHandJointRow; LIVE_HAND_ROW_COUNT] {
        let mut rows = [LiveHandJointRow::default(); LIVE_HAND_ROW_COUNT];
        let mut raw_scene_rows = [LiveHandJointRow::default(); LIVE_HAND_ROW_COUNT];
        let mut spatial_viewer_world_rows = [LiveHandJointRow::default(); LIVE_HAND_ROW_COUNT];
        if !self.status.tracker_ready || self.local_space == openxr_sys::Space::NULL {
            self.status.frame_ready = false;
            clear_last_live_hand_rows();
            self.maybe_log_status();
            return rows;
        }

        let Some(xr_time) = self.current_xr_time() else {
            self.status.frame_ready = false;
            clear_last_live_hand_rows();
            self.maybe_log_status();
            return rows;
        };
        self.status.timestamp_ns = xr_time.as_nanos();
        self.status.left_active = false;
        self.status.right_active = false;
        self.status.active_hand_count = 0;
        self.status.visualizable_joint_count = 0;
        self.status.compact_runtime_joint_pose_count = 0;
        self.status.compact_tip_length_count = 0;
        let scene_transform = current_live_hand_scene_transform();
        let view_mapping = self.current_view_panel_mapping(xr_time);
        let spatial_viewer_world_mapping = self.current_view_spatial_viewer_world_mapping(xr_time);
        self.status.view_pose_ready = view_mapping.is_some();
        self.status.coordinate_mapping = if view_mapping.is_some() {
            "viewer-relative-openxr-to-spatial-sdk-panel-basis"
        } else {
            "raw-openxr-local-floor-to-spatial-sdk-scene-fallback"
        };

        let left = self.locate_hand(
            0,
            xr_time,
            &mut rows,
            &mut raw_scene_rows,
            &mut spatial_viewer_world_rows,
            scene_transform,
            view_mapping,
            spatial_viewer_world_mapping,
        );
        let right = self.locate_hand(
            1,
            xr_time,
            &mut rows,
            &mut raw_scene_rows,
            &mut spatial_viewer_world_rows,
            scene_transform,
            view_mapping,
            spatial_viewer_world_mapping,
        );
        self.status.left_locate_status = left.status;
        self.status.right_locate_status = right.status;
        self.status.left_active = left.active;
        self.status.right_active = right.active;
        self.status.active_hand_count = left.active as u32 + right.active as u32;
        self.status.visualizable_joint_count =
            left.visualizable_joint_count + right.visualizable_joint_count;
        self.status.compact_runtime_joint_pose_count =
            left.compact_runtime_joint_pose_count + right.compact_runtime_joint_pose_count;
        self.status.compact_tip_length_count =
            left.compact_tip_length_count + right.compact_tip_length_count;
        self.status.frame_ready = self.status.active_hand_count > 0;
        if self.status.frame_ready {
            self.frame_counter = self.frame_counter.wrapping_add(1);
            self.status.frame_index = self.frame_counter;
            self.status.input_ready = true;
            self.status.fallback_reason = "none".to_string();
        } else {
            self.status.fallback_reason = "no-active-live-hand-joints".to_string();
            clear_last_live_hand_rows();
        }
        self.maybe_log_status();
        if self.status.frame_ready {
            store_last_live_hand_rows(&rows, &self.status);
            store_last_live_hand_raw_scene_rows(&raw_scene_rows, &self.status);
            if spatial_viewer_world_mapping.is_some() {
                store_last_live_hand_spatial_viewer_world_rows(
                    &spatial_viewer_world_rows,
                    &self.status,
                );
            } else {
                clear_last_live_hand_spatial_viewer_world_rows();
            }
        }
        rows
    }

    fn current_view_panel_mapping(
        &mut self,
        time: openxr_sys::Time,
    ) -> Option<LiveHandViewPanelMapping> {
        let Some(panel_basis) = current_live_hand_panel_basis() else {
            self.status.view_locate_status = "panel-basis-missing".to_string();
            return None;
        };
        self.current_view_target_mapping(
            time,
            [
                panel_basis.center[0] - panel_basis.forward[0] * panel_basis.target_distance_meters,
                panel_basis.center[1] - panel_basis.forward[1] * panel_basis.target_distance_meters,
                panel_basis.center[2] - panel_basis.forward[2] * panel_basis.target_distance_meters,
            ],
            panel_basis.right,
            panel_basis.up,
            panel_basis.forward,
            Some("panel-basis-ready"),
        )
    }

    fn current_view_spatial_viewer_world_mapping(
        &mut self,
        time: openxr_sys::Time,
    ) -> Option<LiveHandSpatialViewerWorldMapping> {
        let basis = current_live_hand_spatial_viewer_world_basis()?;
        let view_pose = self.current_view_pose(time, None)?;
        let (parity, parity_changed) = current_live_hand_spatial_viewer_world_parity();
        if parity_changed {
            self.spatial_viewer_world_registration_log_count = 0;
        }
        let fresh_mapping = spatial_viewer_world_mapping_from_view(basis, view_pose, parity);
        let per_poll_registration = live_hand_spatial_viewer_world_registration_per_poll();
        let stable_basis_ready = spatial_viewer_world_basis_ready_for_stable_registration(basis);
        let mut initialized_now = false;
        let mapping = if per_poll_registration {
            fresh_mapping
        } else {
            let registration = live_hand_spatial_viewer_world_registration();
            let mut slot = registration.lock().ok()?;
            match *slot {
                Some(mapping) => mapping,
                None if !stable_basis_ready => {
                    if self.spatial_viewer_world_registration_log_count < 4 {
                        self.spatial_viewer_world_registration_log_count += 1;
                        android_log_info(
                            "RQKuramotoSpatialNative",
                            &format!(
                                "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=live-hand-spatial-viewer-world-registration-deferred renderPolicy=native-vulkan-wsi-surface-panel liveHandCoordinateTransform=openxr-local-floor-to-spatial-sdk-viewer-world-current-registration liveHandSpatialWorldRegistration=stable-openxr-view-to-spatial-sdk-viewer-world liveHandSpatialWorldRegistrationReason=startup-placeholder-spatial-viewer-basis liveHandSpatialViewerWorldBasisCenterM={:.4};{:.4};{:.4} liveHandOpenXrViewPositionM={:.4};{:.4};{:.4}",
                                basis.center[0],
                                basis.center[1],
                                basis.center[2],
                                view_pose.position[0],
                                view_pose.position[1],
                                view_pose.position[2],
                            ),
                        );
                    }
                    return None;
                }
                None => {
                    *slot = Some(fresh_mapping);
                    initialized_now = true;
                    fresh_mapping
                }
            }
        };
        if self.spatial_viewer_world_registration_log_count < 4 {
            self.spatial_viewer_world_registration_log_count += 1;
            android_log_info(
                "RQKuramotoSpatialNative",
                &format!(
                    "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=live-hand-spatial-viewer-world-registration-updated renderPolicy=native-vulkan-wsi-surface-panel liveHandCoordinateTransform=openxr-local-floor-to-spatial-sdk-viewer-world-current-registration liveHandSpatialWorldRegistration={} liveHandSpatialWorldRegistrationModeProperty={} liveHandSpatialWorldRegistrationInitializedNow={} liveHandSpatialWorldRegistrationStableBasisReady={} liveHandSpatialViewerWorldBasisCenterM={:.4};{:.4};{:.4} liveHandOpenXrViewPositionM={:.4};{:.4};{:.4} liveHandSpatialWorldRegistrationOriginM={:.4};{:.4};{:.4} liveHandSpatialWorldRegistrationOrientationXYZW={:.5};{:.5};{:.5};{:.5}",
                    if per_poll_registration {
                        "per-poll-openxr-view-to-spatial-sdk-viewer-world"
                    } else {
                        "stable-openxr-view-to-spatial-sdk-viewer-world"
                    },
                    LIVE_HAND_SPATIAL_VIEWER_WORLD_REGISTRATION_MODE_PROPERTY,
                    bool_token(initialized_now),
                    bool_token(stable_basis_ready),
                    basis.center[0],
                    basis.center[1],
                    basis.center[2],
                    view_pose.position[0],
                    view_pose.position[1],
                    view_pose.position[2],
                    mapping.origin[0],
                    mapping.origin[1],
                    mapping.origin[2],
                    mapping.map_orientation[0],
                    mapping.map_orientation[1],
                    mapping.map_orientation[2],
                    mapping.map_orientation[3],
                ),
            );
            android_log_info(
                "RQKuramotoSpatialNative",
                &format!(
                    "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=live-hand-spatial-viewer-world-registration-diagnostic renderPolicy=native-vulkan-wsi-surface-panel liveHandCoordinateTransform=openxr-local-floor-to-spatial-sdk-viewer-world-current-registration liveHandSpatialWorldRegistrationParityProperty={} liveHandSpatialWorldRegistrationParity={} liveHandSpatialWorldRegistrationParitySigns={:.0};{:.0};{:.0} liveHandSpatialWorldRegistrationOrientationAdjusted={} liveHandSpatialWorldRegistrationRawBasisDeterminant={:.3} liveHandSpatialWorldRegistrationSpatialBasisDeterminant={:.3} liveHandSpatialWorldRegistrationParityDeterminant={:.3} liveHandSpatialWorldRegistrationEffectivePositionDeterminant={:.3} liveHandSpatialWorldRegistrationRightDot={:.3} liveHandSpatialWorldRegistrationUpDot={:.3} liveHandSpatialWorldRegistrationForwardDot={:.3} liveHandSpatialWorldRegistrationCandidateParityDeterminants=none:{:.0};flip-x:{:.0};flip-y:{:.0};flip-z:{:.0};yaw-180:{:.0}",
                    LIVE_HAND_SPATIAL_VIEWER_WORLD_REGISTRATION_PARITY_PROPERTY,
                    mapping.parity.token(),
                    mapping.parity.signs()[0],
                    mapping.parity.signs()[1],
                    mapping.parity.signs()[2],
                    bool_token(mapping.parity.applies_to_orientation()),
                    mapping.raw_basis_determinant,
                    mapping.spatial_basis_determinant,
                    mapping.parity_determinant,
                    mapping.effective_position_determinant,
                    dot_vec3(
                        map_live_hand_spatial_viewer_world_vector(mapping.raw_right, mapping),
                        mapping.spatial_right,
                    ),
                    dot_vec3(
                        map_live_hand_spatial_viewer_world_vector(mapping.raw_up, mapping),
                        mapping.spatial_up,
                    ),
                    dot_vec3(
                        map_live_hand_spatial_viewer_world_vector(mapping.raw_forward, mapping),
                        mapping.spatial_forward,
                    ),
                    LiveHandSpatialViewerWorldParity::None.determinant(),
                    LiveHandSpatialViewerWorldParity::FlipX.determinant(),
                    LiveHandSpatialViewerWorldParity::FlipY.determinant(),
                    LiveHandSpatialViewerWorldParity::FlipZ.determinant(),
                    LiveHandSpatialViewerWorldParity::Yaw180.determinant(),
                ),
            );
        }
        Some(mapping)
    }

    fn current_view_target_mapping(
        &mut self,
        time: openxr_sys::Time,
        target_origin: [f32; 3],
        target_right: [f32; 3],
        target_up: [f32; 3],
        target_forward: [f32; 3],
        status_suffix: Option<&'static str>,
    ) -> Option<LiveHandViewPanelMapping> {
        let view_pose = self.current_view_pose(time, status_suffix)?;
        let raw_right = rotate_vector_by_quat(view_pose.orientation, [1.0, 0.0, 0.0]);
        let raw_up = rotate_vector_by_quat(view_pose.orientation, [0.0, 1.0, 0.0]);
        let raw_forward = rotate_vector_by_quat(view_pose.orientation, [0.0, 0.0, -1.0]);
        let panel_orientation = quat_from_basis(
            target_right,
            target_up,
            [-target_forward[0], -target_forward[1], -target_forward[2]],
        );
        let map_orientation =
            multiply_quat_xyzw(panel_orientation, inverse_quat_xyzw(view_pose.orientation));
        if let Some(suffix) = status_suffix {
            self.status.view_locate_status =
                format!("ready-view-count-{}-{}", view_pose.view_count, suffix);
        }
        Some(LiveHandViewPanelMapping {
            view_position: view_pose.position,
            raw_right,
            raw_up,
            raw_forward,
            scene_eye_position: target_origin,
            panel_right: target_right,
            panel_up: target_up,
            panel_forward: target_forward,
            map_orientation,
        })
    }

    fn current_view_pose(
        &mut self,
        time: openxr_sys::Time,
        status_suffix: Option<&'static str>,
    ) -> Option<LiveHandViewPose> {
        let Some(locate_views) = self.locate_views else {
            if status_suffix.is_some() {
                self.status.view_locate_status = "locate-views-function-missing".to_string();
            }
            return None;
        };
        let locate_info = openxr_sys::ViewLocateInfo {
            ty: openxr_sys::ViewLocateInfo::TYPE,
            next: ptr::null(),
            view_configuration_type: openxr_sys::ViewConfigurationType::PRIMARY_STEREO,
            display_time: time,
            space: self.local_space,
        };
        let mut view_state = openxr_sys::ViewState {
            ty: openxr_sys::ViewState::TYPE,
            next: ptr::null_mut(),
            view_state_flags: openxr_sys::ViewStateFlags::from_raw(0),
        };
        let mut views = [default_view(), default_view()];
        let mut view_count = 0_u32;
        let result = unsafe {
            locate_views(
                self.session,
                &locate_info,
                &mut view_state,
                views.len() as u32,
                &mut view_count,
                views.as_mut_ptr(),
            )
        };
        if result != openxr_sys::Result::SUCCESS {
            if status_suffix.is_some() {
                self.status.view_locate_status =
                    format!("locate-views-{}", xr_result_token(result));
            }
            return None;
        }
        if view_count < 1 {
            if status_suffix.is_some() {
                self.status.view_locate_status = "locate-views-empty".to_string();
            }
            return None;
        }
        if !view_state
            .view_state_flags
            .contains(openxr_sys::ViewStateFlags::POSITION_VALID)
            || !view_state
                .view_state_flags
                .contains(openxr_sys::ViewStateFlags::ORIENTATION_VALID)
        {
            if status_suffix.is_some() {
                self.status.view_locate_status = format!(
                    "locate-views-invalid-flags-{:?}",
                    view_state.view_state_flags
                );
            }
            return None;
        }
        let view_count_usize = (view_count as usize).min(views.len());
        let mut view_position = [0.0_f32; 3];
        for view in views.iter().take(view_count_usize) {
            view_position[0] += view.pose.position.x;
            view_position[1] += view.pose.position.y;
            view_position[2] += view.pose.position.z;
        }
        let inv_view_count = 1.0 / view_count_usize.max(1) as f32;
        view_position[0] *= inv_view_count;
        view_position[1] *= inv_view_count;
        view_position[2] *= inv_view_count;
        let (view_orientation, well_formed) = normalize_orientation_or_identity([
            views[0].pose.orientation.x,
            views[0].pose.orientation.y,
            views[0].pose.orientation.z,
            views[0].pose.orientation.w,
        ]);
        if !well_formed {
            if status_suffix.is_some() {
                self.status.view_locate_status = "locate-views-orientation-invalid".to_string();
            }
            return None;
        }
        Some(LiveHandViewPose {
            position: view_position,
            orientation: view_orientation,
            view_count: view_count_usize,
        })
    }

    pub(crate) unsafe fn destroy(&mut self) {
        if let Some(destroy_hand_tracker) = self.destroy_hand_tracker {
            for tracker in self.trackers.iter_mut().filter_map(Option::take) {
                let _ = destroy_hand_tracker(tracker);
            }
        }
        if self.local_space != openxr_sys::Space::NULL {
            if let Some(destroy_space) = self.destroy_space {
                let _ = destroy_space(self.local_space);
            }
            self.local_space = openxr_sys::Space::NULL;
        }
    }

    fn query_system_hand_support(&self, system_id: openxr_sys::SystemId) -> bool {
        let Some(get_system_properties) = self.get_system_properties else {
            return false;
        };
        let mut hand_properties = openxr_sys::SystemHandTrackingPropertiesEXT {
            ty: openxr_sys::SystemHandTrackingPropertiesEXT::TYPE,
            next: ptr::null_mut(),
            supports_hand_tracking: openxr_sys::FALSE,
        };
        let mut properties = openxr_sys::SystemProperties::out(
            (&mut hand_properties as *mut openxr_sys::SystemHandTrackingPropertiesEXT)
                .cast::<openxr_sys::BaseOutStructure>(),
        );
        let result =
            unsafe { get_system_properties(self.instance, system_id, properties.as_mut_ptr()) };
        result == openxr_sys::Result::SUCCESS
            && hand_properties.supports_hand_tracking == openxr_sys::TRUE
    }

    fn create_tracker(
        &mut self,
        create_hand_tracker: openxr_sys::pfn::CreateHandTrackerEXT,
        hand: openxr_sys::HandEXT,
        side: &'static str,
    ) -> Option<openxr_sys::HandTrackerEXT> {
        let create_info = openxr_sys::HandTrackerCreateInfoEXT {
            ty: openxr_sys::HandTrackerCreateInfoEXT::TYPE,
            next: ptr::null(),
            hand,
            hand_joint_set: openxr_sys::HandJointSetEXT::DEFAULT,
        };
        let mut tracker = openxr_sys::HandTrackerEXT::NULL;
        let result = unsafe { create_hand_tracker(self.session, &create_info, &mut tracker) };
        if result == openxr_sys::Result::SUCCESS && tracker != openxr_sys::HandTrackerEXT::NULL {
            Some(tracker)
        } else {
            let token = format!("{side}-create-{}", xr_result_token(result));
            if side == "left" {
                self.status.left_locate_status = token;
            } else {
                self.status.right_locate_status = token;
            }
            None
        }
    }

    fn current_xr_time(&mut self) -> Option<openxr_sys::Time> {
        let mut timespec = libc::timespec {
            tv_sec: 0,
            tv_nsec: 0,
        };
        let clock_result = unsafe { clock_gettime(CLOCK_MONOTONIC, &mut timespec) };
        if clock_result != 0 {
            self.status.fallback_reason = "clock_gettime-failed".to_string();
            self.status.time_source = "clock_gettime-failed";
            return None;
        }
        if let Some(convert) = self.convert_timespec_time_to_time {
            let mut time = openxr_sys::Time::from_nanos(0);
            let result = unsafe { convert(self.instance, &timespec, &mut time) };
            if result == openxr_sys::Result::SUCCESS {
                self.status.time_source = "XR_KHR_convert_timespec_time_CLOCK_MONOTONIC";
                return Some(time);
            }
            self.status.fallback_reason =
                format!("xrConvertTimespecTimeToTimeKHR-{}", xr_result_token(result));
            return None;
        }
        let nanos = timespec
            .tv_sec
            .saturating_mul(1_000_000_000)
            .saturating_add(timespec.tv_nsec as i64);
        self.status.time_source = "CLOCK_MONOTONIC_direct_XrTime_fallback";
        Some(openxr_sys::Time::from_nanos(nanos))
    }

    fn locate_hand(
        &mut self,
        hand_index: usize,
        time: openxr_sys::Time,
        rows: &mut [LiveHandJointRow; LIVE_HAND_ROW_COUNT],
        raw_scene_rows: &mut [LiveHandJointRow; LIVE_HAND_ROW_COUNT],
        spatial_viewer_world_rows: &mut [LiveHandJointRow; LIVE_HAND_ROW_COUNT],
        scene_transform: LiveHandSceneTransform,
        view_mapping: Option<LiveHandViewPanelMapping>,
        spatial_viewer_world_mapping: Option<LiveHandSpatialViewerWorldMapping>,
    ) -> HandLocateResult {
        let Some(tracker) = self.trackers[hand_index] else {
            return HandLocateResult::inactive("tracker-missing");
        };
        let Some(locate_hand_joints) = self.locate_hand_joints else {
            return HandLocateResult::inactive("locate-function-missing");
        };

        let locate_info = openxr_sys::HandJointsLocateInfoEXT {
            ty: openxr_sys::HandJointsLocateInfoEXT::TYPE,
            next: ptr::null(),
            base_space: self.local_space,
            time,
        };
        let mut locations = [openxr_sys::HandJointLocationEXT::default(); LIVE_HAND_JOINT_COUNT];
        let mut joint_locations = openxr_sys::HandJointLocationsEXT {
            ty: openxr_sys::HandJointLocationsEXT::TYPE,
            next: ptr::null_mut(),
            is_active: openxr_sys::FALSE,
            joint_count: LIVE_HAND_JOINT_COUNT as u32,
            joint_locations: locations.as_mut_ptr(),
        };
        let result = unsafe { locate_hand_joints(tracker, &locate_info, &mut joint_locations) };
        if result != openxr_sys::Result::SUCCESS {
            return HandLocateResult::inactive(format!("locate-{}", xr_result_token(result)));
        }
        if joint_locations.is_active != openxr_sys::TRUE {
            return HandLocateResult::inactive("inactive");
        }

        let row_base = hand_index * LIVE_HAND_JOINT_COUNT;
        let mut valid_position_count = 0_usize;
        let mut pose_valid_count = 0_usize;
        let compact_runtime_joint_pose_count = compact_runtime_joint_pose_count(&locations);
        let compact_tip_length_count = compact_tip_length_count(&locations);
        for (joint_index, location) in locations.iter().enumerate() {
            let position_valid = location
                .location_flags
                .contains(openxr_sys::SpaceLocationFlags::POSITION_VALID);
            if !position_valid {
                continue;
            }
            let orientation_valid = location
                .location_flags
                .contains(openxr_sys::SpaceLocationFlags::ORIENTATION_VALID);
            let position_tracked = location
                .location_flags
                .contains(openxr_sys::SpaceLocationFlags::POSITION_TRACKED);
            let (orientation, orientation_well_formed) = normalize_orientation_or_identity([
                location.pose.orientation.x,
                location.pose.orientation.y,
                location.pose.orientation.z,
                location.pose.orientation.w,
            ]);
            let raw_position = [
                location.pose.position.x,
                location.pose.position.y,
                location.pose.position.z,
            ];
            let (raw_scene_position, raw_scene_orientation) =
                apply_live_hand_scene_transform(raw_position, orientation, scene_transform);
            let (scene_position, scene_orientation) = if let Some(mapping) = view_mapping {
                apply_live_hand_view_panel_mapping(raw_position, orientation, mapping)
            } else {
                (raw_scene_position, raw_scene_orientation)
            };
            let spatial_viewer_world_sample = spatial_viewer_world_mapping.map(|mapping| {
                apply_live_hand_spatial_viewer_world_mapping(raw_position, orientation, mapping)
            });
            let pose_valid = orientation_valid && orientation_well_formed;
            valid_position_count += 1;
            if pose_valid {
                pose_valid_count += 1;
            }
            let radius = location
                .radius
                .abs()
                .max(LIVE_HAND_MIN_JOINT_RADIUS_METERS)
                .min(0.030);
            let status = [
                1.0,
                pose_valid as u32 as f32,
                hand_index as f32,
                position_tracked as u32 as f32,
            ];
            rows[row_base + joint_index] =
                live_hand_joint_row(scene_position, scene_orientation, radius, status);
            raw_scene_rows[row_base + joint_index] =
                live_hand_joint_row(raw_scene_position, raw_scene_orientation, radius, status);
            if let Some((spatial_position, spatial_orientation)) = spatial_viewer_world_sample {
                spatial_viewer_world_rows[row_base + joint_index] =
                    live_hand_joint_row(spatial_position, spatial_orientation, radius, status);
            }
        }
        if valid_position_count == 0 {
            return HandLocateResult::inactive("active-no-valid-position-joints");
        }
        let skinning_ready = compact_runtime_joint_pose_count
            == LIVE_HAND_COMPACT_RUNTIME_OPENXR_JOINTS.len()
            && compact_tip_length_count == LIVE_HAND_COMPACT_TIP_OPENXR_PAIRS.len();
        HandLocateResult {
            active: skinning_ready,
            visualizable_joint_count: pose_valid_count as u32,
            compact_runtime_joint_pose_count: compact_runtime_joint_pose_count as u32,
            compact_tip_length_count: compact_tip_length_count as u32,
            status: format!(
                "active-position-joints-{}-pose-joints-{}-compact-runtime-joints-{}-tip-lengths-{}-skinning-ready-{}",
                valid_position_count,
                pose_valid_count,
                compact_runtime_joint_pose_count,
                compact_tip_length_count,
                bool_token(skinning_ready)
            ),
        }
    }

    fn maybe_log_status(&mut self) {
        if self.status.frame_ready && !self.first_frame_logged {
            self.first_frame_logged = true;
            self.log_status("live-hand-joints-frame-ready");
            return;
        }
        if self.status_log_count < 3 {
            self.log_status("live-hand-joints-status");
        }
    }

    fn log_status(&mut self, status: &'static str) {
        self.status_log_count = self.status_log_count.saturating_add(1);
        android_log_info(
            "RQKuramotoSpatialNative",
            &format!(
                "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status={} renderPolicy=native-vulkan-wsi-surface-panel {} {}",
                status,
                self.handles.marker_fields(),
                self.status.marker_fields(),
            ),
        );
    }
}

fn live_hand_joint_row(
    position: [f32; 3],
    orientation_xyzw: [f32; 4],
    radius: f32,
    status: [f32; 4],
) -> LiveHandJointRow {
    LiveHandJointRow {
        position_radius: [position[0], position[1], position[2], radius],
        status,
        orientation_xyzw,
    }
}

fn compact_runtime_joint_pose_count(
    locations: &[openxr_sys::HandJointLocationEXT; LIVE_HAND_JOINT_COUNT],
) -> usize {
    LIVE_HAND_COMPACT_RUNTIME_OPENXR_JOINTS
        .iter()
        .filter(|index| openxr_location_pose_valid(&locations[**index]))
        .count()
}

fn compact_tip_length_count(
    locations: &[openxr_sys::HandJointLocationEXT; LIVE_HAND_JOINT_COUNT],
) -> usize {
    LIVE_HAND_COMPACT_TIP_OPENXR_PAIRS
        .iter()
        .filter(|(distal, tip)| {
            openxr_location_pose_valid(&locations[*distal])
                && openxr_location_pose_valid(&locations[*tip])
        })
        .count()
}

fn openxr_location_pose_valid(location: &openxr_sys::HandJointLocationEXT) -> bool {
    if !location
        .location_flags
        .contains(openxr_sys::SpaceLocationFlags::POSITION_VALID)
        || !location
            .location_flags
            .contains(openxr_sys::SpaceLocationFlags::ORIENTATION_VALID)
    {
        return false;
    }
    normalize_orientation_or_identity([
        location.pose.orientation.x,
        location.pose.orientation.y,
        location.pose.orientation.z,
        location.pose.orientation.w,
    ])
    .1
}

struct HandLocateResult {
    active: bool,
    visualizable_joint_count: u32,
    compact_runtime_joint_pose_count: u32,
    compact_tip_length_count: u32,
    status: String,
}

impl HandLocateResult {
    fn inactive(status: impl Into<String>) -> Self {
        Self {
            active: false,
            visualizable_joint_count: 0,
            compact_runtime_joint_pose_count: 0,
            compact_tip_length_count: 0,
            status: status.into(),
        }
    }
}

#[derive(Clone, Copy, Debug)]
struct LiveHandSceneTransform {
    offset_meters: [f32; 3],
    yaw_degrees: f32,
    horizontal_sign: f32,
}

#[derive(Clone, Copy, Debug)]
struct LiveHandPanelBasis {
    center: [f32; 3],
    right: [f32; 3],
    up: [f32; 3],
    forward: [f32; 3],
    target_distance_meters: f32,
}

#[derive(Clone, Copy, Debug)]
struct LiveHandSpatialViewerWorldBasis {
    center: [f32; 3],
    right: [f32; 3],
    up: [f32; 3],
    forward: [f32; 3],
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum LiveHandSpatialViewerWorldParity {
    None,
    FlipX,
    FlipY,
    FlipZ,
    Yaw180,
}

impl LiveHandSpatialViewerWorldParity {
    fn parse(value: Option<String>) -> Self {
        match value.as_deref().map(marker_token).as_deref() {
            None => Self::FlipX,
            Some("none") | Some("off") | Some("disabled") | Some("0") | Some("false") => Self::None,
            Some("flip-x") | Some("x") | Some("mirror-x") | Some("reflect-x") => Self::FlipX,
            Some("flip-y") | Some("y") | Some("mirror-y") | Some("reflect-y") => Self::FlipY,
            Some("flip-z") | Some("z") | Some("depth") | Some("mirror-z") | Some("reflect-z") => {
                Self::FlipZ
            }
            Some("yaw-180") | Some("flip-xz") | Some("xz") | Some("half-turn")
            | Some("rotate-y-180") => Self::Yaw180,
            _ => Self::FlipX,
        }
    }

    fn from_bits(bits: u32) -> Self {
        match bits {
            1 => Self::FlipX,
            2 => Self::FlipY,
            3 => Self::FlipZ,
            4 => Self::Yaw180,
            _ => Self::None,
        }
    }

    fn bits(self) -> u32 {
        match self {
            Self::None => 0,
            Self::FlipX => 1,
            Self::FlipY => 2,
            Self::FlipZ => 3,
            Self::Yaw180 => 4,
        }
    }

    fn token(self) -> &'static str {
        match self {
            Self::None => "none",
            Self::FlipX => "flip-x",
            Self::FlipY => "flip-y",
            Self::FlipZ => "flip-z",
            Self::Yaw180 => "yaw-180",
        }
    }

    fn signs(self) -> [f32; 3] {
        match self {
            Self::None => [1.0, 1.0, 1.0],
            Self::FlipX => [-1.0, 1.0, 1.0],
            Self::FlipY => [1.0, -1.0, 1.0],
            Self::FlipZ => [1.0, 1.0, -1.0],
            Self::Yaw180 => [-1.0, 1.0, -1.0],
        }
    }

    fn determinant(self) -> f32 {
        match self {
            Self::None | Self::Yaw180 => 1.0,
            Self::FlipX | Self::FlipY | Self::FlipZ => -1.0,
        }
    }

    fn applies_to_orientation(self) -> bool {
        self.determinant() > 0.0
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum LiveHandSpatialViewerWorldReflectionOrientation {
    None,
    LocalX,
    LocalY,
    LocalZ,
}

impl LiveHandSpatialViewerWorldReflectionOrientation {
    fn parse(value: Option<String>) -> Self {
        match value.as_deref().map(marker_token).as_deref() {
            None => Self::LocalY,
            Some("none") | Some("off") | Some("disabled") | Some("0") | Some("false") => Self::None,
            Some("local-x") | Some("x") | Some("reflect-local-x") => Self::LocalX,
            Some("local-y") | Some("y") | Some("reflect-local-y") => Self::LocalY,
            Some("local-z") | Some("z") | Some("reflect-local-z") => Self::LocalZ,
            _ => Self::LocalY,
        }
    }

    fn from_bits(bits: u32) -> Self {
        match bits {
            1 => Self::LocalX,
            2 => Self::LocalY,
            3 => Self::LocalZ,
            _ => Self::None,
        }
    }

    fn bits(self) -> u32 {
        match self {
            Self::None => 0,
            Self::LocalX => 1,
            Self::LocalY => 2,
            Self::LocalZ => 3,
        }
    }

    fn token(self) -> &'static str {
        match self {
            Self::None => "none",
            Self::LocalX => "local-x",
            Self::LocalY => "local-y",
            Self::LocalZ => "local-z",
        }
    }

    fn local_signs(self) -> [f32; 3] {
        match self {
            Self::None => [1.0, 1.0, 1.0],
            Self::LocalX => [-1.0, 1.0, 1.0],
            Self::LocalY => [1.0, -1.0, 1.0],
            Self::LocalZ => [1.0, 1.0, -1.0],
        }
    }

    fn adjusts_orientation(self) -> bool {
        self != Self::None
    }
}

#[derive(Clone, Copy, Debug)]
struct LiveHandSpatialViewerWorldMapping {
    origin: [f32; 3],
    map_orientation: [f32; 4],
    raw_right: [f32; 3],
    raw_up: [f32; 3],
    raw_forward: [f32; 3],
    spatial_right: [f32; 3],
    spatial_up: [f32; 3],
    spatial_forward: [f32; 3],
    parity: LiveHandSpatialViewerWorldParity,
    raw_basis_determinant: f32,
    spatial_basis_determinant: f32,
    parity_determinant: f32,
    effective_position_determinant: f32,
}

#[derive(Clone, Copy, Debug)]
struct LiveHandViewPose {
    position: [f32; 3],
    orientation: [f32; 4],
    view_count: usize,
}

#[derive(Clone, Copy, Debug)]
struct LiveHandViewPanelMapping {
    view_position: [f32; 3],
    raw_right: [f32; 3],
    raw_up: [f32; 3],
    raw_forward: [f32; 3],
    scene_eye_position: [f32; 3],
    panel_right: [f32; 3],
    panel_up: [f32; 3],
    panel_forward: [f32; 3],
    map_orientation: [f32; 4],
}

pub(crate) fn store_live_hand_panel_basis(
    center: [f32; 3],
    right: [f32; 3],
    up: [f32; 3],
    target_distance_meters: f32,
    valid: bool,
) {
    let right = normalize_vec3_or(right, [1.0, 0.0, 0.0]);
    let up = normalize_vec3_or(up, [0.0, 1.0, 0.0]);
    let forward = normalize_vec3_or(cross_vec3(up, right), [0.0, 0.0, -1.0]);
    LIVE_HAND_PANEL_CENTER_X_BITS.store(center[0].to_bits(), Ordering::Relaxed);
    LIVE_HAND_PANEL_CENTER_Y_BITS.store(center[1].to_bits(), Ordering::Relaxed);
    LIVE_HAND_PANEL_CENTER_Z_BITS.store(center[2].to_bits(), Ordering::Relaxed);
    LIVE_HAND_PANEL_RIGHT_X_BITS.store(right[0].to_bits(), Ordering::Relaxed);
    LIVE_HAND_PANEL_RIGHT_Y_BITS.store(right[1].to_bits(), Ordering::Relaxed);
    LIVE_HAND_PANEL_RIGHT_Z_BITS.store(right[2].to_bits(), Ordering::Relaxed);
    LIVE_HAND_PANEL_UP_X_BITS.store(up[0].to_bits(), Ordering::Relaxed);
    LIVE_HAND_PANEL_UP_Y_BITS.store(up[1].to_bits(), Ordering::Relaxed);
    LIVE_HAND_PANEL_UP_Z_BITS.store(up[2].to_bits(), Ordering::Relaxed);
    LIVE_HAND_PANEL_FORWARD_X_BITS.store(forward[0].to_bits(), Ordering::Relaxed);
    LIVE_HAND_PANEL_FORWARD_Y_BITS.store(forward[1].to_bits(), Ordering::Relaxed);
    LIVE_HAND_PANEL_FORWARD_Z_BITS.store(forward[2].to_bits(), Ordering::Relaxed);
    LIVE_HAND_PANEL_TARGET_DISTANCE_BITS.store(
        target_distance_meters.clamp(0.20, 1.50).to_bits(),
        Ordering::Relaxed,
    );
    LIVE_HAND_PANEL_BASIS_VALID.store(valid, Ordering::Relaxed);
}

pub(crate) fn store_live_hand_spatial_viewer_world_basis(
    center: [f32; 3],
    right: [f32; 3],
    up: [f32; 3],
    valid: bool,
) {
    if !valid {
        clear_live_hand_spatial_viewer_world_registration();
    }
    let right = normalize_vec3_or(right, [1.0, 0.0, 0.0]);
    let up = normalize_vec3_or(up, [0.0, 1.0, 0.0]);
    let forward = normalize_vec3_or(cross_vec3(up, right), [0.0, 0.0, -1.0]);
    LIVE_HAND_SPATIAL_VIEWER_WORLD_CENTER_X_BITS.store(center[0].to_bits(), Ordering::Relaxed);
    LIVE_HAND_SPATIAL_VIEWER_WORLD_CENTER_Y_BITS.store(center[1].to_bits(), Ordering::Relaxed);
    LIVE_HAND_SPATIAL_VIEWER_WORLD_CENTER_Z_BITS.store(center[2].to_bits(), Ordering::Relaxed);
    LIVE_HAND_SPATIAL_VIEWER_WORLD_RIGHT_X_BITS.store(right[0].to_bits(), Ordering::Relaxed);
    LIVE_HAND_SPATIAL_VIEWER_WORLD_RIGHT_Y_BITS.store(right[1].to_bits(), Ordering::Relaxed);
    LIVE_HAND_SPATIAL_VIEWER_WORLD_RIGHT_Z_BITS.store(right[2].to_bits(), Ordering::Relaxed);
    LIVE_HAND_SPATIAL_VIEWER_WORLD_UP_X_BITS.store(up[0].to_bits(), Ordering::Relaxed);
    LIVE_HAND_SPATIAL_VIEWER_WORLD_UP_Y_BITS.store(up[1].to_bits(), Ordering::Relaxed);
    LIVE_HAND_SPATIAL_VIEWER_WORLD_UP_Z_BITS.store(up[2].to_bits(), Ordering::Relaxed);
    LIVE_HAND_SPATIAL_VIEWER_WORLD_FORWARD_X_BITS.store(forward[0].to_bits(), Ordering::Relaxed);
    LIVE_HAND_SPATIAL_VIEWER_WORLD_FORWARD_Y_BITS.store(forward[1].to_bits(), Ordering::Relaxed);
    LIVE_HAND_SPATIAL_VIEWER_WORLD_FORWARD_Z_BITS.store(forward[2].to_bits(), Ordering::Relaxed);
    LIVE_HAND_SPATIAL_VIEWER_WORLD_BASIS_VALID.store(valid, Ordering::Relaxed);
}

fn current_live_hand_panel_basis() -> Option<LiveHandPanelBasis> {
    if !LIVE_HAND_PANEL_BASIS_VALID.load(Ordering::Relaxed) {
        return None;
    }
    Some(LiveHandPanelBasis {
        center: [
            f32::from_bits(LIVE_HAND_PANEL_CENTER_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_PANEL_CENTER_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_PANEL_CENTER_Z_BITS.load(Ordering::Relaxed)),
        ],
        right: [
            f32::from_bits(LIVE_HAND_PANEL_RIGHT_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_PANEL_RIGHT_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_PANEL_RIGHT_Z_BITS.load(Ordering::Relaxed)),
        ],
        up: [
            f32::from_bits(LIVE_HAND_PANEL_UP_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_PANEL_UP_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_PANEL_UP_Z_BITS.load(Ordering::Relaxed)),
        ],
        forward: [
            f32::from_bits(LIVE_HAND_PANEL_FORWARD_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_PANEL_FORWARD_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_PANEL_FORWARD_Z_BITS.load(Ordering::Relaxed)),
        ],
        target_distance_meters: f32::from_bits(
            LIVE_HAND_PANEL_TARGET_DISTANCE_BITS.load(Ordering::Relaxed),
        ),
    })
}

fn current_live_hand_spatial_viewer_world_basis() -> Option<LiveHandSpatialViewerWorldBasis> {
    if !LIVE_HAND_SPATIAL_VIEWER_WORLD_BASIS_VALID.load(Ordering::Relaxed) {
        return None;
    }
    Some(LiveHandSpatialViewerWorldBasis {
        center: [
            f32::from_bits(LIVE_HAND_SPATIAL_VIEWER_WORLD_CENTER_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_SPATIAL_VIEWER_WORLD_CENTER_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_SPATIAL_VIEWER_WORLD_CENTER_Z_BITS.load(Ordering::Relaxed)),
        ],
        right: [
            f32::from_bits(LIVE_HAND_SPATIAL_VIEWER_WORLD_RIGHT_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_SPATIAL_VIEWER_WORLD_RIGHT_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_SPATIAL_VIEWER_WORLD_RIGHT_Z_BITS.load(Ordering::Relaxed)),
        ],
        up: [
            f32::from_bits(LIVE_HAND_SPATIAL_VIEWER_WORLD_UP_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_SPATIAL_VIEWER_WORLD_UP_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_SPATIAL_VIEWER_WORLD_UP_Z_BITS.load(Ordering::Relaxed)),
        ],
        forward: [
            f32::from_bits(LIVE_HAND_SPATIAL_VIEWER_WORLD_FORWARD_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_SPATIAL_VIEWER_WORLD_FORWARD_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_SPATIAL_VIEWER_WORLD_FORWARD_Z_BITS.load(Ordering::Relaxed)),
        ],
    })
}

pub(crate) fn clear_live_hand_spatial_viewer_world_registration() {
    if let Some(registration) = LIVE_HAND_SPATIAL_VIEWER_WORLD_REGISTRATION.get() {
        if let Ok(mut slot) = registration.lock() {
            *slot = None;
        }
    }
}

fn live_hand_spatial_viewer_world_registration(
) -> &'static Mutex<Option<LiveHandSpatialViewerWorldMapping>> {
    LIVE_HAND_SPATIAL_VIEWER_WORLD_REGISTRATION.get_or_init(|| Mutex::new(None))
}

fn live_hand_spatial_viewer_world_registration_per_poll() -> bool {
    let mode = android_system_property(LIVE_HAND_SPATIAL_VIEWER_WORLD_REGISTRATION_MODE_PROPERTY)
        .map(|value| marker_token(&value));
    matches!(
        mode.as_deref(),
        Some("per-poll") | Some("live") | Some("head-locked") | Some("current")
    )
}

fn current_live_hand_spatial_viewer_world_parity() -> (LiveHandSpatialViewerWorldParity, bool) {
    let parity = LiveHandSpatialViewerWorldParity::parse(android_system_property(
        LIVE_HAND_SPATIAL_VIEWER_WORLD_REGISTRATION_PARITY_PROPERTY,
    ));
    let old_bits =
        LIVE_HAND_SPATIAL_VIEWER_WORLD_PARITY_BITS.swap(parity.bits(), Ordering::Relaxed);
    let previous = LiveHandSpatialViewerWorldParity::from_bits(old_bits);
    let changed = previous != parity;
    if changed {
        clear_live_hand_spatial_viewer_world_registration();
        log_live_hand_spatial_viewer_world_parity_update(previous, parity);
    }
    (parity, changed)
}

fn current_live_hand_spatial_viewer_world_reflection_orientation(
) -> (LiveHandSpatialViewerWorldReflectionOrientation, bool) {
    let orientation = LiveHandSpatialViewerWorldReflectionOrientation::parse(
        android_system_property(LIVE_HAND_SPATIAL_VIEWER_WORLD_REFLECTION_ORIENTATION_PROPERTY),
    );
    let old_bits = LIVE_HAND_SPATIAL_VIEWER_WORLD_REFLECTION_ORIENTATION_BITS
        .swap(orientation.bits(), Ordering::Relaxed);
    let previous = LiveHandSpatialViewerWorldReflectionOrientation::from_bits(old_bits);
    let changed = previous != orientation;
    if changed {
        log_live_hand_spatial_viewer_world_reflection_orientation_update(previous, orientation);
    }
    (orientation, changed)
}

fn log_live_hand_spatial_viewer_world_reflection_orientation_update(
    previous: LiveHandSpatialViewerWorldReflectionOrientation,
    current: LiveHandSpatialViewerWorldReflectionOrientation,
) {
    android_log_info(
        "RQKuramotoSpatialNative",
        &format!(
            "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=live-hand-spatial-viewer-world-reflection-orientation-updated renderPolicy=native-vulkan-wsi-surface-panel transport=android-system-property liveHandCoordinateTransform=openxr-local-floor-to-spatial-sdk-viewer-world-current-registration liveHandSpatialWorldReflectionOrientationProperty={} liveHandSpatialWorldReflectionOrientationPrevious={} liveHandSpatialWorldReflectionOrientation={}",
            LIVE_HAND_SPATIAL_VIEWER_WORLD_REFLECTION_ORIENTATION_PROPERTY,
            previous.token(),
            current.token(),
        ),
    );
}

fn log_live_hand_spatial_viewer_world_parity_update(
    previous: LiveHandSpatialViewerWorldParity,
    current: LiveHandSpatialViewerWorldParity,
) {
    let log_count = LIVE_HAND_SPATIAL_VIEWER_WORLD_PARITY_LOG_COUNT.fetch_add(1, Ordering::Relaxed);
    if log_count >= 8 {
        return;
    }
    android_log_info(
        "RQKuramotoSpatialNative",
        &format!(
            "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=live-hand-spatial-viewer-world-registration-parity-updated renderPolicy=native-vulkan-wsi-surface-panel transport=android-system-property liveHandCoordinateTransform=openxr-local-floor-to-spatial-sdk-viewer-world-current-registration liveHandSpatialWorldRegistrationParityProperty={} liveHandSpatialWorldRegistrationParityPrevious={} liveHandSpatialWorldRegistrationParity={} liveHandSpatialWorldRegistrationCleared=true",
            LIVE_HAND_SPATIAL_VIEWER_WORLD_REGISTRATION_PARITY_PROPERTY,
            previous.token(),
            current.token(),
        ),
    );
}

fn spatial_viewer_world_basis_ready_for_stable_registration(
    basis: LiveHandSpatialViewerWorldBasis,
) -> bool {
    const STARTUP_CENTER_EPSILON_METERS: f32 = 0.001;
    let default_center_delta =
        (basis.center[0]).abs() + (basis.center[1]).abs() + (basis.center[2] - 2.0).abs();
    default_center_delta > STARTUP_CENTER_EPSILON_METERS
}

fn spatial_viewer_world_mapping_from_view(
    basis: LiveHandSpatialViewerWorldBasis,
    view_pose: LiveHandViewPose,
    parity: LiveHandSpatialViewerWorldParity,
) -> LiveHandSpatialViewerWorldMapping {
    let raw_right = rotate_vector_by_quat(view_pose.orientation, [1.0, 0.0, 0.0]);
    let raw_up = rotate_vector_by_quat(view_pose.orientation, [0.0, 1.0, 0.0]);
    let raw_forward = rotate_vector_by_quat(view_pose.orientation, [0.0, 0.0, -1.0]);
    let signs = parity.signs();
    let orientation_right = if parity.applies_to_orientation() {
        scale_vec3(basis.right, signs[0])
    } else {
        basis.right
    };
    let orientation_up = if parity.applies_to_orientation() {
        scale_vec3(basis.up, signs[1])
    } else {
        basis.up
    };
    let orientation_forward = if parity.applies_to_orientation() {
        scale_vec3(basis.forward, signs[2])
    } else {
        basis.forward
    };
    let spatial_viewer_orientation = quat_from_basis(
        orientation_right,
        orientation_up,
        [
            -orientation_forward[0],
            -orientation_forward[1],
            -orientation_forward[2],
        ],
    );
    let (map_orientation, _) = normalize_orientation_or_identity(multiply_quat_xyzw(
        spatial_viewer_orientation,
        inverse_quat_xyzw(view_pose.orientation),
    ));
    let raw_basis_determinant = determinant_from_basis(raw_right, raw_up, raw_forward);
    let spatial_basis_determinant = determinant_from_basis(basis.right, basis.up, basis.forward);
    let parity_determinant = parity.determinant();
    let effective_position_determinant = if raw_basis_determinant.abs() > 0.000001 {
        spatial_basis_determinant * parity_determinant / raw_basis_determinant
    } else {
        0.0
    };
    let mapped_view_position = map_spatial_viewer_world_vector(
        view_pose.position,
        raw_right,
        raw_up,
        raw_forward,
        basis.right,
        basis.up,
        basis.forward,
        parity,
    );
    LiveHandSpatialViewerWorldMapping {
        origin: [
            basis.center[0] - mapped_view_position[0],
            basis.center[1] - mapped_view_position[1],
            basis.center[2] - mapped_view_position[2],
        ],
        map_orientation,
        raw_right,
        raw_up,
        raw_forward,
        spatial_right: basis.right,
        spatial_up: basis.up,
        spatial_forward: basis.forward,
        parity,
        raw_basis_determinant,
        spatial_basis_determinant,
        parity_determinant,
        effective_position_determinant,
    }
}

pub(crate) fn copy_last_live_hand_rows() -> Option<LiveHandRowsSnapshot> {
    copy_rows_snapshot(&LIVE_HAND_LAST_ROWS)
}

fn store_last_live_hand_rows(
    rows: &[LiveHandJointRow; LIVE_HAND_ROW_COUNT],
    status: &LiveHandJointStatus,
) {
    store_rows_snapshot(&LIVE_HAND_LAST_ROWS, rows, status);
}

pub(crate) fn copy_last_live_hand_raw_scene_rows() -> Option<LiveHandRowsSnapshot> {
    copy_rows_snapshot(&LIVE_HAND_LAST_RAW_SCENE_ROWS)
}

fn store_last_live_hand_raw_scene_rows(
    rows: &[LiveHandJointRow; LIVE_HAND_ROW_COUNT],
    status: &LiveHandJointStatus,
) {
    store_rows_snapshot(&LIVE_HAND_LAST_RAW_SCENE_ROWS, rows, status);
}

pub(crate) fn copy_last_live_hand_spatial_viewer_world_rows() -> Option<LiveHandRowsSnapshot> {
    copy_rows_snapshot(&LIVE_HAND_LAST_SPATIAL_VIEWER_WORLD_ROWS)
}

fn store_last_live_hand_spatial_viewer_world_rows(
    rows: &[LiveHandJointRow; LIVE_HAND_ROW_COUNT],
    status: &LiveHandJointStatus,
) {
    store_rows_snapshot(&LIVE_HAND_LAST_SPATIAL_VIEWER_WORLD_ROWS, rows, status);
}

fn clear_last_live_hand_rows() {
    clear_rows_snapshot(&LIVE_HAND_LAST_ROWS);
    clear_rows_snapshot(&LIVE_HAND_LAST_RAW_SCENE_ROWS);
    clear_rows_snapshot(&LIVE_HAND_LAST_SPATIAL_VIEWER_WORLD_ROWS);
}

fn clear_last_live_hand_spatial_viewer_world_rows() {
    clear_rows_snapshot(&LIVE_HAND_LAST_SPATIAL_VIEWER_WORLD_ROWS);
}

fn copy_rows_snapshot(
    cache: &OnceLock<Mutex<Option<LiveHandRowsSnapshot>>>,
) -> Option<LiveHandRowsSnapshot> {
    let cache = cache.get()?;
    cache.lock().ok().and_then(|snapshot| *snapshot)
}

fn store_rows_snapshot(
    cache: &OnceLock<Mutex<Option<LiveHandRowsSnapshot>>>,
    rows: &[LiveHandJointRow; LIVE_HAND_ROW_COUNT],
    status: &LiveHandJointStatus,
) {
    let cache = cache.get_or_init(|| Mutex::new(None));
    if let Ok(mut snapshot) = cache.lock() {
        *snapshot = Some(LiveHandRowsSnapshot {
            rows: *rows,
            active_hand_count: status.active_hand_count,
            frame_index: status.frame_index,
            timestamp_ns: status.timestamp_ns,
        });
    }
}

fn clear_rows_snapshot(cache: &OnceLock<Mutex<Option<LiveHandRowsSnapshot>>>) {
    let Some(cache) = cache.get() else {
        return;
    };
    if let Ok(mut snapshot) = cache.lock() {
        *snapshot = None;
    }
}

fn current_live_hand_scene_transform() -> LiveHandSceneTransform {
    let changed = poll_live_hand_scene_scalar_property(
        LIVE_HAND_SCENE_OFFSET_X_PROPERTY,
        &LIVE_HAND_SCENE_OFFSET_X_BITS,
        -4.0,
        4.0,
    ) | poll_live_hand_scene_scalar_property(
        LIVE_HAND_SCENE_OFFSET_Y_PROPERTY,
        &LIVE_HAND_SCENE_OFFSET_Y_BITS,
        -4.0,
        4.0,
    ) | poll_live_hand_scene_scalar_property(
        LIVE_HAND_SCENE_OFFSET_Z_PROPERTY,
        &LIVE_HAND_SCENE_OFFSET_Z_BITS,
        -4.0,
        4.0,
    ) | poll_live_hand_scene_scalar_property(
        LIVE_HAND_SCENE_YAW_DEGREES_PROPERTY,
        &LIVE_HAND_SCENE_YAW_DEGREES_BITS,
        -360.0,
        360.0,
    ) | poll_live_hand_scene_sign_property(
        LIVE_HAND_SCENE_HORIZONTAL_SIGN_PROPERTY,
        &LIVE_HAND_SCENE_HORIZONTAL_SIGN_BITS,
    );
    let transform = LiveHandSceneTransform {
        offset_meters: [
            f32::from_bits(LIVE_HAND_SCENE_OFFSET_X_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_SCENE_OFFSET_Y_BITS.load(Ordering::Relaxed)),
            f32::from_bits(LIVE_HAND_SCENE_OFFSET_Z_BITS.load(Ordering::Relaxed)),
        ],
        yaw_degrees: f32::from_bits(LIVE_HAND_SCENE_YAW_DEGREES_BITS.load(Ordering::Relaxed)),
        horizontal_sign: f32::from_bits(
            LIVE_HAND_SCENE_HORIZONTAL_SIGN_BITS.load(Ordering::Relaxed),
        ),
    };
    if changed {
        log_live_hand_scene_transform(transform);
    }
    transform
}

fn poll_live_hand_scene_scalar_property(
    property_name: &str,
    storage: &AtomicU32,
    min_value: f32,
    max_value: f32,
) -> bool {
    let Some(raw_value) = android_system_property(property_name) else {
        return false;
    };
    let Ok(parsed_value) = raw_value.parse::<f32>() else {
        return false;
    };
    if !parsed_value.is_finite() {
        return false;
    }
    let clamped_value = parsed_value.clamp(min_value, max_value);
    let old_bits = storage.swap(clamped_value.to_bits(), Ordering::Relaxed);
    old_bits != clamped_value.to_bits()
}

fn poll_live_hand_scene_sign_property(property_name: &str, storage: &AtomicU32) -> bool {
    let Some(raw_value) = android_system_property(property_name) else {
        return false;
    };
    let Ok(parsed_value) = raw_value.parse::<f32>() else {
        return false;
    };
    if !parsed_value.is_finite() {
        return false;
    }
    let sign_value = if parsed_value < 0.0 {
        -1.0_f32
    } else {
        1.0_f32
    };
    let old_bits = storage.swap(sign_value.to_bits(), Ordering::Relaxed);
    old_bits != sign_value.to_bits()
}

fn log_live_hand_scene_transform(transform: LiveHandSceneTransform) {
    let log_count = LIVE_HAND_SCENE_TRANSFORM_LOG_COUNT.fetch_add(1, Ordering::Relaxed);
    if log_count >= 8 {
        return;
    }
    android_log_info(
        "RQKuramotoSpatialNative",
        &format!(
            "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=live-hand-scene-transform-hotload-updated renderPolicy=native-vulkan-wsi-surface-panel transport=android-system-property liveHandCoordinateTransform=raw-openxr-local-floor-to-spatial-sdk-scene liveHandSceneTransformSource=runtime-hotload-android-property liveHandSceneOffsetProperties={};{};{} liveHandSceneYawProperty={} liveHandSceneHorizontalSignProperty={} liveHandSceneOffsetM={:.3};{:.3};{:.3} liveHandSceneYawDegrees={:.3} liveHandSceneHorizontalSign={:.3}",
            LIVE_HAND_SCENE_OFFSET_X_PROPERTY,
            LIVE_HAND_SCENE_OFFSET_Y_PROPERTY,
            LIVE_HAND_SCENE_OFFSET_Z_PROPERTY,
            LIVE_HAND_SCENE_YAW_DEGREES_PROPERTY,
            LIVE_HAND_SCENE_HORIZONTAL_SIGN_PROPERTY,
            transform.offset_meters[0],
            transform.offset_meters[1],
            transform.offset_meters[2],
            transform.yaw_degrees,
            transform.horizontal_sign,
        ),
    );
}

fn apply_live_hand_scene_transform(
    position: [f32; 3],
    orientation_xyzw: [f32; 4],
    transform: LiveHandSceneTransform,
) -> ([f32; 3], [f32; 4]) {
    let yaw_radians = transform.yaw_degrees.to_radians();
    let (sin_yaw, cos_yaw) = yaw_radians.sin_cos();
    let rotated_position = [
        cos_yaw * position[0] + sin_yaw * position[2],
        position[1],
        -sin_yaw * position[0] + cos_yaw * position[2],
    ];
    let half_yaw = yaw_radians * 0.5;
    let yaw_quat = [0.0, half_yaw.sin(), 0.0, half_yaw.cos()];
    let (rotated_orientation, _) =
        normalize_orientation_or_identity(multiply_quat_xyzw(yaw_quat, orientation_xyzw));
    (
        [
            rotated_position[0] * transform.horizontal_sign + transform.offset_meters[0],
            rotated_position[1] + transform.offset_meters[1],
            rotated_position[2] + transform.offset_meters[2],
        ],
        rotated_orientation,
    )
}

fn apply_live_hand_view_panel_mapping(
    position: [f32; 3],
    orientation_xyzw: [f32; 4],
    mapping: LiveHandViewPanelMapping,
) -> ([f32; 3], [f32; 4]) {
    let scene_position = apply_live_hand_view_panel_position_mapping(position, mapping);
    let (scene_orientation, _) = normalize_orientation_or_identity(multiply_quat_xyzw(
        mapping.map_orientation,
        orientation_xyzw,
    ));
    (scene_position, scene_orientation)
}

fn apply_live_hand_spatial_viewer_world_mapping(
    position: [f32; 3],
    orientation_xyzw: [f32; 4],
    mapping: LiveHandSpatialViewerWorldMapping,
) -> ([f32; 3], [f32; 4]) {
    let mapped_position = map_live_hand_spatial_viewer_world_vector(position, mapping);
    let scene_position = [
        mapping.origin[0] + mapped_position[0],
        mapping.origin[1] + mapped_position[1],
        mapping.origin[2] + mapped_position[2],
    ];
    let reflection_orientation = current_live_hand_spatial_viewer_world_reflection_orientation().0;
    let scene_orientation =
        if mapping.parity.determinant() < 0.0 && reflection_orientation.adjusts_orientation() {
            map_live_hand_spatial_viewer_world_reflected_orientation(
                orientation_xyzw,
                mapping,
                reflection_orientation,
            )
        } else {
            normalize_orientation_or_identity(multiply_quat_xyzw(
                mapping.map_orientation,
                orientation_xyzw,
            ))
            .0
        };
    (scene_position, scene_orientation)
}

fn apply_live_hand_view_panel_position_mapping(
    position: [f32; 3],
    mapping: LiveHandViewPanelMapping,
) -> [f32; 3] {
    let rel = [
        position[0] - mapping.view_position[0],
        position[1] - mapping.view_position[1],
        position[2] - mapping.view_position[2],
    ];
    let rel_right = dot_vec3(rel, mapping.raw_right);
    let rel_up = dot_vec3(rel, mapping.raw_up);
    let rel_forward = dot_vec3(rel, mapping.raw_forward);
    let scene_position = [
        mapping.scene_eye_position[0]
            + mapping.panel_right[0] * rel_right
            + mapping.panel_up[0] * rel_up
            + mapping.panel_forward[0] * rel_forward,
        mapping.scene_eye_position[1]
            + mapping.panel_right[1] * rel_right
            + mapping.panel_up[1] * rel_up
            + mapping.panel_forward[1] * rel_forward,
        mapping.scene_eye_position[2]
            + mapping.panel_right[2] * rel_right
            + mapping.panel_up[2] * rel_up
            + mapping.panel_forward[2] * rel_forward,
    ];
    scene_position
}

fn map_live_hand_spatial_viewer_world_vector(
    vector: [f32; 3],
    mapping: LiveHandSpatialViewerWorldMapping,
) -> [f32; 3] {
    map_spatial_viewer_world_vector(
        vector,
        mapping.raw_right,
        mapping.raw_up,
        mapping.raw_forward,
        mapping.spatial_right,
        mapping.spatial_up,
        mapping.spatial_forward,
        mapping.parity,
    )
}

fn map_live_hand_spatial_viewer_world_reflected_orientation(
    orientation_xyzw: [f32; 4],
    mapping: LiveHandSpatialViewerWorldMapping,
    reflection_orientation: LiveHandSpatialViewerWorldReflectionOrientation,
) -> [f32; 4] {
    let local_signs = reflection_orientation.local_signs();
    let raw_right = scale_vec3(
        rotate_vector_by_quat(orientation_xyzw, [1.0, 0.0, 0.0]),
        local_signs[0],
    );
    let raw_up = scale_vec3(
        rotate_vector_by_quat(orientation_xyzw, [0.0, 1.0, 0.0]),
        local_signs[1],
    );
    let raw_back = scale_vec3(
        rotate_vector_by_quat(orientation_xyzw, [0.0, 0.0, 1.0]),
        local_signs[2],
    );
    quat_from_basis(
        normalize_vec3_or(
            map_live_hand_spatial_viewer_world_vector(raw_right, mapping),
            [1.0, 0.0, 0.0],
        ),
        normalize_vec3_or(
            map_live_hand_spatial_viewer_world_vector(raw_up, mapping),
            [0.0, 1.0, 0.0],
        ),
        normalize_vec3_or(
            map_live_hand_spatial_viewer_world_vector(raw_back, mapping),
            [0.0, 0.0, 1.0],
        ),
    )
}

fn map_spatial_viewer_world_vector(
    vector: [f32; 3],
    raw_right: [f32; 3],
    raw_up: [f32; 3],
    raw_forward: [f32; 3],
    spatial_right: [f32; 3],
    spatial_up: [f32; 3],
    spatial_forward: [f32; 3],
    parity: LiveHandSpatialViewerWorldParity,
) -> [f32; 3] {
    let signs = parity.signs();
    let rel_right = dot_vec3(vector, raw_right) * signs[0];
    let rel_up = dot_vec3(vector, raw_up) * signs[1];
    let rel_forward = dot_vec3(vector, raw_forward) * signs[2];
    [
        spatial_right[0] * rel_right + spatial_up[0] * rel_up + spatial_forward[0] * rel_forward,
        spatial_right[1] * rel_right + spatial_up[1] * rel_up + spatial_forward[1] * rel_forward,
        spatial_right[2] * rel_right + spatial_up[2] * rel_up + spatial_forward[2] * rel_forward,
    ]
}

fn default_view() -> openxr_sys::View {
    openxr_sys::View {
        ty: openxr_sys::View::TYPE,
        next: ptr::null_mut(),
        pose: openxr_sys::Posef {
            orientation: openxr_sys::Quaternionf {
                x: 0.0,
                y: 0.0,
                z: 0.0,
                w: 1.0,
            },
            position: openxr_sys::Vector3f::default(),
        },
        fov: openxr_sys::Fovf {
            angle_left: 0.0,
            angle_right: 0.0,
            angle_up: 0.0,
            angle_down: 0.0,
        },
    }
}

fn multiply_quat_xyzw(a: [f32; 4], b: [f32; 4]) -> [f32; 4] {
    [
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    ]
}

fn inverse_quat_xyzw(quat: [f32; 4]) -> [f32; 4] {
    let (q, _) = normalize_orientation_or_identity(quat);
    [-q[0], -q[1], -q[2], q[3]]
}

fn rotate_vector_by_quat(quat: [f32; 4], vector: [f32; 3]) -> [f32; 3] {
    let (q, _) = normalize_orientation_or_identity(quat);
    let q_vec = [q[0], q[1], q[2]];
    let uv = cross_vec3(q_vec, vector);
    let uuv = cross_vec3(q_vec, uv);
    [
        vector[0] + uv[0] * (2.0 * q[3]) + uuv[0] * 2.0,
        vector[1] + uv[1] * (2.0 * q[3]) + uuv[1] * 2.0,
        vector[2] + uv[2] * (2.0 * q[3]) + uuv[2] * 2.0,
    ]
}

fn quat_from_basis(right: [f32; 3], up: [f32; 3], back: [f32; 3]) -> [f32; 4] {
    let m00 = right[0];
    let m01 = up[0];
    let m02 = back[0];
    let m10 = right[1];
    let m11 = up[1];
    let m12 = back[1];
    let m20 = right[2];
    let m21 = up[2];
    let m22 = back[2];
    let trace = m00 + m11 + m22;
    let quat = if trace > 0.0 {
        let s = (trace + 1.0).sqrt() * 2.0;
        [(m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s]
    } else if m00 > m11 && m00 > m22 {
        let s = (1.0 + m00 - m11 - m22).sqrt() * 2.0;
        [0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s]
    } else if m11 > m22 {
        let s = (1.0 + m11 - m00 - m22).sqrt() * 2.0;
        [(m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s]
    } else {
        let s = (1.0 + m22 - m00 - m11).sqrt() * 2.0;
        [(m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s]
    };
    normalize_orientation_or_identity(quat).0
}

fn normalize_vec3_or(value: [f32; 3], fallback: [f32; 3]) -> [f32; 3] {
    let len_sq = dot_vec3(value, value);
    if len_sq > 0.00000001 {
        let inv_len = len_sq.sqrt().recip();
        [value[0] * inv_len, value[1] * inv_len, value[2] * inv_len]
    } else {
        fallback
    }
}

fn dot_vec3(a: [f32; 3], b: [f32; 3]) -> f32 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

fn scale_vec3(value: [f32; 3], scale: f32) -> [f32; 3] {
    [value[0] * scale, value[1] * scale, value[2] * scale]
}

fn cross_vec3(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}

fn determinant_from_basis(right: [f32; 3], up: [f32; 3], forward: [f32; 3]) -> f32 {
    dot_vec3(right, cross_vec3(up, forward))
}

fn android_system_property(name: &str) -> Option<String> {
    #[cfg(target_os = "android")]
    {
        const PROP_VALUE_MAX: usize = 92;
        let name = CString::new(name).ok()?;
        let mut value = [0 as c_char; PROP_VALUE_MAX];
        let len = unsafe { __system_property_get(name.as_ptr(), value.as_mut_ptr()) };
        if len <= 0 {
            return None;
        }
        let text = unsafe { CStr::from_ptr(value.as_ptr()) }
            .to_string_lossy()
            .trim()
            .to_string();
        if text.is_empty() {
            None
        } else {
            Some(text)
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = name;
        None
    }
}

fn create_hand_reference_space(
    session: openxr_sys::Session,
    create_reference_space: openxr_sys::pfn::CreateReferenceSpace,
) -> Result<(openxr_sys::Space, &'static str), String> {
    match create_reference_space_of_type(
        session,
        create_reference_space,
        openxr_sys::ReferenceSpaceType::LOCAL_FLOOR,
    ) {
        Ok(space) => return Ok((space, "LOCAL_FLOOR")),
        Err(local_floor_error) => {
            let local = create_reference_space_of_type(
                session,
                create_reference_space,
                openxr_sys::ReferenceSpaceType::LOCAL,
            );
            return local
                .map(|space| (space, "LOCAL"))
                .map_err(|local_error| format!("{local_floor_error}_fallback-{local_error}"));
        }
    }
}

fn create_reference_space_of_type(
    session: openxr_sys::Session,
    create_reference_space: openxr_sys::pfn::CreateReferenceSpace,
    reference_space_type: openxr_sys::ReferenceSpaceType,
) -> Result<openxr_sys::Space, String> {
    let create_info = openxr_sys::ReferenceSpaceCreateInfo {
        ty: openxr_sys::ReferenceSpaceCreateInfo::TYPE,
        next: ptr::null(),
        reference_space_type,
        pose_in_reference_space: openxr_sys::Posef {
            orientation: openxr_sys::Quaternionf {
                x: 0.0,
                y: 0.0,
                z: 0.0,
                w: 1.0,
            },
            position: openxr_sys::Vector3f {
                x: 0.0,
                y: 0.0,
                z: 0.0,
            },
        },
    };
    let mut space = openxr_sys::Space::NULL;
    let result = unsafe { create_reference_space(session, &create_info, &mut space) };
    if result == openxr_sys::Result::SUCCESS && space != openxr_sys::Space::NULL {
        Ok(space)
    } else {
        Err(format!(
            "xrCreateReferenceSpace-{}-{}",
            reference_space_token(reference_space_type),
            xr_result_token(result)
        ))
    }
}

fn reference_space_token(reference_space_type: openxr_sys::ReferenceSpaceType) -> &'static str {
    if reference_space_type == openxr_sys::ReferenceSpaceType::LOCAL_FLOOR {
        "LOCAL_FLOOR"
    } else if reference_space_type == openxr_sys::ReferenceSpaceType::LOCAL {
        "LOCAL"
    } else if reference_space_type == openxr_sys::ReferenceSpaceType::STAGE {
        "STAGE"
    } else {
        "unknown"
    }
}

fn resolve_openxr_function(
    instance: openxr_sys::Instance,
    get_instance_proc_addr: openxr_sys::pfn::GetInstanceProcAddr,
    name: &str,
) -> ResolvedOpenXrFunction {
    let function_name = CString::new(name).expect("static OpenXR symbol must not contain NUL");
    let mut function: Option<openxr_sys::pfn::VoidFunction> = None;
    let result = unsafe { get_instance_proc_addr(instance, function_name.as_ptr(), &mut function) };
    ResolvedOpenXrFunction {
        resolved: result == openxr_sys::Result::SUCCESS && function.is_some(),
        result: xr_result_token(result),
        function,
    }
}

fn normalize_orientation_or_identity(value: [f32; 4]) -> ([f32; 4], bool) {
    if !value.iter().all(|component| component.is_finite()) {
        return ([0.0, 0.0, 0.0, 1.0], false);
    }
    let len_sq =
        value[0] * value[0] + value[1] * value[1] + value[2] * value[2] + value[3] * value[3];
    if len_sq <= 0.000001 {
        return ([0.0, 0.0, 0.0, 1.0], false);
    }
    let inv_len = len_sq.sqrt().recip();
    (
        [
            value[0] * inv_len,
            value[1] * inv_len,
            value[2] * inv_len,
            value[3] * inv_len,
        ],
        true,
    )
}

fn typed_function<T>(function: Option<openxr_sys::pfn::VoidFunction>) -> Option<T> {
    function.map(|function| unsafe { mem::transmute_copy(&function) })
}

fn xr_result_token(result: openxr_sys::Result) -> String {
    if result == openxr_sys::Result::SUCCESS {
        "success".to_string()
    } else {
        format!("code_{}", result.into_raw())
    }
}
