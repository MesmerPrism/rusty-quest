#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::collections::VecDeque;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use ash::vk;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};

use crate::bool_token;
use crate::camera_hwb_marker::log_camera_hwb_marker as log_marker;

pub(crate) const CAMERA_LATENCY_DEFAULT_FRAME_WAIT_MS: u32 = 2;
pub(crate) const CAMERA_LATENCY_DEFAULT_SUMMARY_INTERVAL_MS: u32 = 1000;
pub(crate) const CAMERA_LATENCY_STRICT_PAIR_MAX_DELTA_NS: u64 = 5_000_000;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub(crate) enum CameraLatencyPoseMode {
    CurrentViewer = 0,
    FrozenWorld = 1,
}

impl CameraLatencyPoseMode {
    pub(crate) fn from_code(code: u32) -> Self {
        if code == Self::FrozenWorld as u32 {
            Self::FrozenWorld
        } else {
            Self::CurrentViewer
        }
    }

    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::CurrentViewer => "current-viewer",
            Self::FrozenWorld => "frozen-world",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub(crate) enum CameraLatencyPresentModePreference {
    Fifo = 0,
    MailboxIfAvailable = 1,
    ImmediateIfAvailable = 2,
    AutoLowLatency = 3,
}

impl CameraLatencyPresentModePreference {
    pub(crate) fn from_code(code: u32) -> Self {
        match code {
            1 => Self::MailboxIfAvailable,
            2 => Self::ImmediateIfAvailable,
            3 => Self::AutoLowLatency,
            _ => Self::Fifo,
        }
    }

    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::Fifo => "fifo",
            Self::MailboxIfAvailable => "mailbox-if-available",
            Self::ImmediateIfAvailable => "immediate-if-available",
            Self::AutoLowLatency => "auto-low-latency",
        }
    }

    pub(crate) fn choose(self, available: &[vk::PresentModeKHR]) -> vk::PresentModeKHR {
        let fifo = || {
            if available.contains(&vk::PresentModeKHR::FIFO) {
                vk::PresentModeKHR::FIFO
            } else {
                available
                    .first()
                    .copied()
                    .unwrap_or(vk::PresentModeKHR::FIFO)
            }
        };
        match self {
            Self::Fifo => fifo(),
            Self::MailboxIfAvailable => {
                if available.contains(&vk::PresentModeKHR::MAILBOX) {
                    vk::PresentModeKHR::MAILBOX
                } else {
                    fifo()
                }
            }
            Self::ImmediateIfAvailable => {
                if available.contains(&vk::PresentModeKHR::IMMEDIATE) {
                    vk::PresentModeKHR::IMMEDIATE
                } else {
                    fifo()
                }
            }
            Self::AutoLowLatency => {
                if available.contains(&vk::PresentModeKHR::MAILBOX) {
                    vk::PresentModeKHR::MAILBOX
                } else if available.contains(&vk::PresentModeKHR::IMMEDIATE) {
                    vk::PresentModeKHR::IMMEDIATE
                } else {
                    fifo()
                }
            }
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub(crate) enum CameraLatencyImageCountPreference {
    MinPlusOne = 0,
    MinSafe = 1,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub(crate) enum CameraLatencyCaptureFpsPreference {
    Default = 0,
    Fps30 = 30,
    Fps45 = 45,
    Fps50 = 50,
    Fps60 = 60,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub(crate) enum CameraLatencyAdoptionCadence {
    EveryAvailable = 0,
    DisplayAligned45 = 45,
}

impl CameraLatencyAdoptionCadence {
    pub(crate) fn from_code(code: u32) -> Self {
        if code == Self::DisplayAligned45 as u32 {
            Self::DisplayAligned45
        } else {
            Self::EveryAvailable
        }
    }

    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::EveryAvailable => "every-available",
            Self::DisplayAligned45 => "display-aligned-45",
        }
    }
}

impl CameraLatencyCaptureFpsPreference {
    pub(crate) fn from_code(code: u32) -> Self {
        match code {
            30 => Self::Fps30,
            45 => Self::Fps45,
            50 => Self::Fps50,
            60 => Self::Fps60,
            _ => Self::Default,
        }
    }

    pub(crate) fn requested_fps(self) -> Option<i32> {
        match self {
            Self::Default => None,
            Self::Fps30 => Some(30),
            Self::Fps45 => Some(45),
            Self::Fps50 => Some(50),
            Self::Fps60 => Some(60),
        }
    }

    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::Default => "camera-default",
            Self::Fps30 => "30",
            Self::Fps45 => "45",
            Self::Fps50 => "50",
            Self::Fps60 => "60",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub(crate) enum CameraLatencyStereoPolicy {
    IndependentLatest = 0,
    StrictTimestampPair = 1,
    MonoDuplicateLeft = 2,
}

impl CameraLatencyStereoPolicy {
    pub(crate) fn from_code(code: u32) -> Self {
        match code {
            1 => Self::StrictTimestampPair,
            2 => Self::MonoDuplicateLeft,
            _ => Self::IndependentLatest,
        }
    }

    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::IndependentLatest => "independent-latest",
            Self::StrictTimestampPair => "strict-timestamp-pair",
            Self::MonoDuplicateLeft => "mono-duplicate-left",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub(crate) enum CameraLatencyReprojectionMode {
    Off = 0,
    RotationOnlyAssumedAge = 1,
    RotationOnlySensorTimestamp = 2,
    RotationOnlySensorTimestampInverse = 3,
    RotationOnlySensorTimestampInverseRollFree = 4,
    RotationOnlySensorTimestampInverseYawOnly = 5,
    RotationOnlySensorTimestampCameraCalibrated = 6,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub(crate) enum CameraLatencyCameraSyncMode {
    EarlyDeleteAhbRetained = 0,
    HoldImageUntilGpuFence = 1,
}

impl CameraLatencyCameraSyncMode {
    pub(crate) fn from_code(code: u32) -> Self {
        if code == Self::HoldImageUntilGpuFence as u32 {
            Self::HoldImageUntilGpuFence
        } else {
            Self::EarlyDeleteAhbRetained
        }
    }

    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::EarlyDeleteAhbRetained => "early-delete-ahb-retained",
            Self::HoldImageUntilGpuFence => "hold-image-until-gpu-fence",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub(crate) enum CameraLatencyCaptureProcessing {
    TemplateDefault = 0,
    NoiseEdgeOff = 1,
}

impl CameraLatencyCaptureProcessing {
    pub(crate) fn from_code(code: u32) -> Self {
        if code == Self::NoiseEdgeOff as u32 {
            Self::NoiseEdgeOff
        } else {
            Self::TemplateDefault
        }
    }

    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::TemplateDefault => "template-default",
            Self::NoiseEdgeOff => "noise-edge-off",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub(crate) enum CameraLatencyIsolationMode {
    NormalComposite = 0,
    OpaqueCameraOnly = 1,
    FreshFrameOnlyPulse = 2,
}

impl CameraLatencyIsolationMode {
    pub(crate) fn from_code(code: u32) -> Self {
        match code {
            1 => Self::OpaqueCameraOnly,
            2 => Self::FreshFrameOnlyPulse,
            _ => Self::NormalComposite,
        }
    }

    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::NormalComposite => "normal-composite",
            Self::OpaqueCameraOnly => "opaque-camera-only",
            Self::FreshFrameOnlyPulse => "fresh-frame-only-pulse",
        }
    }
}

impl CameraLatencyReprojectionMode {
    pub(crate) fn from_code(code: u32) -> Self {
        match code {
            1 => Self::RotationOnlyAssumedAge,
            2 => Self::RotationOnlySensorTimestamp,
            3 => Self::RotationOnlySensorTimestampInverse,
            4 => Self::RotationOnlySensorTimestampInverseRollFree,
            5 => Self::RotationOnlySensorTimestampInverseYawOnly,
            6 => Self::RotationOnlySensorTimestampCameraCalibrated,
            _ => Self::Off,
        }
    }

    pub(crate) fn rotation_enabled(self) -> bool {
        self != Self::Off
    }

    fn uses_sensor_timestamp(self) -> bool {
        matches!(
            self,
            Self::RotationOnlySensorTimestamp
                | Self::RotationOnlySensorTimestampInverse
                | Self::RotationOnlySensorTimestampInverseRollFree
                | Self::RotationOnlySensorTimestampInverseYawOnly
                | Self::RotationOnlySensorTimestampCameraCalibrated
        )
    }

    fn inverse_direction(self) -> bool {
        matches!(
            self,
            Self::RotationOnlySensorTimestampInverse
                | Self::RotationOnlySensorTimestampInverseRollFree
                | Self::RotationOnlySensorTimestampInverseYawOnly
        )
    }

    fn axis_filter(self) -> CameraLatencyRotationAxisFilter {
        match self {
            Self::RotationOnlySensorTimestampInverseRollFree => {
                CameraLatencyRotationAxisFilter::RollFree
            }
            Self::RotationOnlySensorTimestampInverseYawOnly => {
                CameraLatencyRotationAxisFilter::YawOnly
            }
            _ => CameraLatencyRotationAxisFilter::Full,
        }
    }

    fn uses_camera_calibration(self) -> bool {
        self == Self::RotationOnlySensorTimestampCameraCalibrated
    }

    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::RotationOnlyAssumedAge => "rotation-only-raw-layer",
            Self::RotationOnlySensorTimestamp => "rotation-only-sensor-timestamp",
            Self::RotationOnlySensorTimestampInverse => "rotation-only-sensor-timestamp-inverse",
            Self::RotationOnlySensorTimestampInverseRollFree => {
                "rotation-only-sensor-timestamp-inverse-roll-free"
            }
            Self::RotationOnlySensorTimestampInverseYawOnly => {
                "rotation-only-sensor-timestamp-inverse-yaw-only"
            }
            Self::RotationOnlySensorTimestampCameraCalibrated => {
                "rotation-only-sensor-timestamp-camera-calibrated"
            }
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum CameraLatencyRotationAxisFilter {
    Full,
    RollFree,
    YawOnly,
}

impl CameraLatencyImageCountPreference {
    pub(crate) fn from_code(code: u32) -> Self {
        if code == Self::MinSafe as u32 {
            Self::MinSafe
        } else {
            Self::MinPlusOne
        }
    }

    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::MinPlusOne => "min-plus-one",
            Self::MinSafe => "min-safe",
        }
    }

    pub(crate) fn choose(self, capabilities: &vk::SurfaceCapabilitiesKHR) -> u32 {
        let requested = match self {
            Self::MinPlusOne => capabilities.min_image_count.saturating_add(1).max(2),
            Self::MinSafe => capabilities.min_image_count.max(2),
        };
        if capabilities.max_image_count > 0 {
            requested.min(capabilities.max_image_count)
        } else {
            requested
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct CameraLatencySettings {
    pub(crate) enabled: bool,
    pub(crate) revision: u64,
    pub(crate) pose_mode: CameraLatencyPoseMode,
    pub(crate) frame_wait_ms: u32,
    pub(crate) summary_interval_ms: u32,
    pub(crate) frame_log: bool,
    pub(crate) present_mode: CameraLatencyPresentModePreference,
    pub(crate) image_count: CameraLatencyImageCountPreference,
    pub(crate) capture_fps: CameraLatencyCaptureFpsPreference,
    pub(crate) camera_sync_mode: CameraLatencyCameraSyncMode,
    pub(crate) capture_processing: CameraLatencyCaptureProcessing,
    pub(crate) adoption_cadence: CameraLatencyAdoptionCadence,
    pub(crate) stereo_policy: CameraLatencyStereoPolicy,
    pub(crate) isolation_mode: CameraLatencyIsolationMode,
    pub(crate) freeze_frame: bool,
    pub(crate) reprojection_mode: CameraLatencyReprojectionMode,
    pub(crate) assumed_capture_age_ms: u32,
    pub(crate) reprojection_fov_degrees: u32,
}

impl Default for CameraLatencySettings {
    fn default() -> Self {
        Self {
            enabled: false,
            revision: 0,
            pose_mode: CameraLatencyPoseMode::CurrentViewer,
            frame_wait_ms: CAMERA_LATENCY_DEFAULT_FRAME_WAIT_MS,
            summary_interval_ms: CAMERA_LATENCY_DEFAULT_SUMMARY_INTERVAL_MS,
            frame_log: false,
            present_mode: CameraLatencyPresentModePreference::Fifo,
            image_count: CameraLatencyImageCountPreference::MinPlusOne,
            capture_fps: CameraLatencyCaptureFpsPreference::Default,
            camera_sync_mode: CameraLatencyCameraSyncMode::EarlyDeleteAhbRetained,
            capture_processing: CameraLatencyCaptureProcessing::TemplateDefault,
            adoption_cadence: CameraLatencyAdoptionCadence::EveryAvailable,
            stereo_policy: CameraLatencyStereoPolicy::IndependentLatest,
            isolation_mode: CameraLatencyIsolationMode::NormalComposite,
            freeze_frame: false,
            reprojection_mode: CameraLatencyReprojectionMode::Off,
            assumed_capture_age_ms: 40,
            reprojection_fov_degrees: 90,
        }
    }
}

impl CameraLatencySettings {
    pub(crate) fn effective_frame_wait_ms(self) -> u32 {
        if self.enabled {
            self.frame_wait_ms.min(10)
        } else {
            CAMERA_LATENCY_DEFAULT_FRAME_WAIT_MS
        }
    }

    pub(crate) fn should_adopt_camera_image(self, frames_presented: u32) -> bool {
        if !self.enabled {
            return true;
        }
        match self.adoption_cadence {
            CameraLatencyAdoptionCadence::EveryAvailable => true,
            CameraLatencyAdoptionCadence::DisplayAligned45 => frames_presented % 2 == 0,
        }
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "cameraLatencyDiagnosticEnabled={} cameraLatencyRevision={} cameraLatencyPoseMode={} cameraLatencyFrameWaitMs={} cameraLatencySummaryIntervalMs={} cameraLatencyFrameLog={} cameraLatencyPresentModeRequested={} cameraLatencyImageCountRequested={} cameraLatencyCaptureFpsRequested={} cameraLatencyCameraSyncRequested={} cameraLatencyCaptureProcessingRequested={} cameraLatencyAdoptionCadence={} cameraLatencyStereoPolicy={} cameraLatencyIsolationMode={} cameraLatencyFreezeFrame={} cameraLatencyReprojectionMode={} cameraLatencyAssumedCaptureAgeMs={} cameraLatencyReprojectionFovDegrees={}",
            bool_token(self.enabled),
            self.revision,
            self.pose_mode.marker_token(),
            self.frame_wait_ms,
            self.summary_interval_ms,
            bool_token(self.frame_log),
            self.present_mode.marker_token(),
            self.image_count.marker_token(),
            self.capture_fps.marker_token(),
            self.camera_sync_mode.marker_token(),
            self.capture_processing.marker_token(),
            self.adoption_cadence.marker_token(),
            self.stereo_policy.marker_token(),
            self.isolation_mode.marker_token(),
            bool_token(self.freeze_frame),
            self.reprojection_mode.marker_token(),
            self.assumed_capture_age_ms,
            self.reprojection_fov_degrees,
        )
    }
}

static CAMERA_LATENCY_ENABLED: AtomicBool = AtomicBool::new(false);
static CAMERA_LATENCY_REVISION: AtomicU64 = AtomicU64::new(0);
static CAMERA_LATENCY_POSE_MODE: AtomicU32 =
    AtomicU32::new(CameraLatencyPoseMode::CurrentViewer as u32);
static CAMERA_LATENCY_FRAME_WAIT_MS: AtomicU32 =
    AtomicU32::new(CAMERA_LATENCY_DEFAULT_FRAME_WAIT_MS);
static CAMERA_LATENCY_SUMMARY_INTERVAL_MS: AtomicU32 =
    AtomicU32::new(CAMERA_LATENCY_DEFAULT_SUMMARY_INTERVAL_MS);
static CAMERA_LATENCY_FRAME_LOG: AtomicBool = AtomicBool::new(false);
static CAMERA_LATENCY_PRESENT_MODE: AtomicU32 =
    AtomicU32::new(CameraLatencyPresentModePreference::Fifo as u32);
static CAMERA_LATENCY_IMAGE_COUNT: AtomicU32 =
    AtomicU32::new(CameraLatencyImageCountPreference::MinPlusOne as u32);
static CAMERA_LATENCY_CAPTURE_FPS: AtomicU32 =
    AtomicU32::new(CameraLatencyCaptureFpsPreference::Default as u32);
static CAMERA_LATENCY_CAMERA_SYNC_MODE: AtomicU32 =
    AtomicU32::new(CameraLatencyCameraSyncMode::EarlyDeleteAhbRetained as u32);
static CAMERA_LATENCY_CAPTURE_PROCESSING: AtomicU32 =
    AtomicU32::new(CameraLatencyCaptureProcessing::TemplateDefault as u32);
static CAMERA_LATENCY_ADOPTION_CADENCE: AtomicU32 =
    AtomicU32::new(CameraLatencyAdoptionCadence::EveryAvailable as u32);
static CAMERA_LATENCY_STEREO_POLICY: AtomicU32 =
    AtomicU32::new(CameraLatencyStereoPolicy::IndependentLatest as u32);
static CAMERA_LATENCY_ISOLATION_MODE: AtomicU32 =
    AtomicU32::new(CameraLatencyIsolationMode::NormalComposite as u32);
static CAMERA_LATENCY_FREEZE_FRAME: AtomicBool = AtomicBool::new(false);
static CAMERA_LATENCY_REPROJECTION_MODE: AtomicU32 =
    AtomicU32::new(CameraLatencyReprojectionMode::Off as u32);
static CAMERA_LATENCY_ASSUMED_CAPTURE_AGE_MS: AtomicU32 = AtomicU32::new(40);
static CAMERA_LATENCY_REPROJECTION_FOV_DEGREES: AtomicU32 = AtomicU32::new(90);

pub(crate) fn current_camera_latency_settings() -> CameraLatencySettings {
    CameraLatencySettings {
        enabled: CAMERA_LATENCY_ENABLED.load(Ordering::Acquire),
        revision: CAMERA_LATENCY_REVISION.load(Ordering::Acquire),
        pose_mode: CameraLatencyPoseMode::from_code(
            CAMERA_LATENCY_POSE_MODE.load(Ordering::Acquire),
        ),
        frame_wait_ms: CAMERA_LATENCY_FRAME_WAIT_MS.load(Ordering::Acquire).min(10),
        summary_interval_ms: CAMERA_LATENCY_SUMMARY_INTERVAL_MS
            .load(Ordering::Acquire)
            .clamp(250, 5000),
        frame_log: CAMERA_LATENCY_FRAME_LOG.load(Ordering::Acquire),
        present_mode: CameraLatencyPresentModePreference::from_code(
            CAMERA_LATENCY_PRESENT_MODE.load(Ordering::Acquire),
        ),
        image_count: CameraLatencyImageCountPreference::from_code(
            CAMERA_LATENCY_IMAGE_COUNT.load(Ordering::Acquire),
        ),
        capture_fps: CameraLatencyCaptureFpsPreference::from_code(
            CAMERA_LATENCY_CAPTURE_FPS.load(Ordering::Acquire),
        ),
        camera_sync_mode: CameraLatencyCameraSyncMode::from_code(
            CAMERA_LATENCY_CAMERA_SYNC_MODE.load(Ordering::Acquire),
        ),
        capture_processing: CameraLatencyCaptureProcessing::from_code(
            CAMERA_LATENCY_CAPTURE_PROCESSING.load(Ordering::Acquire),
        ),
        adoption_cadence: CameraLatencyAdoptionCadence::from_code(
            CAMERA_LATENCY_ADOPTION_CADENCE.load(Ordering::Acquire),
        ),
        stereo_policy: CameraLatencyStereoPolicy::from_code(
            CAMERA_LATENCY_STEREO_POLICY.load(Ordering::Acquire),
        ),
        isolation_mode: CameraLatencyIsolationMode::from_code(
            CAMERA_LATENCY_ISOLATION_MODE.load(Ordering::Acquire),
        ),
        freeze_frame: CAMERA_LATENCY_FREEZE_FRAME.load(Ordering::Acquire),
        reprojection_mode: CameraLatencyReprojectionMode::from_code(
            CAMERA_LATENCY_REPROJECTION_MODE.load(Ordering::Acquire),
        ),
        assumed_capture_age_ms: CAMERA_LATENCY_ASSUMED_CAPTURE_AGE_MS
            .load(Ordering::Acquire)
            .min(120),
        reprojection_fov_degrees: CAMERA_LATENCY_REPROJECTION_FOV_DEGREES
            .load(Ordering::Acquire)
            .clamp(60, 130),
    }
}

pub(crate) fn camera_latency_per_frame_log_enabled() -> bool {
    CAMERA_LATENCY_ENABLED.load(Ordering::Acquire)
        && CAMERA_LATENCY_FRAME_LOG.load(Ordering::Acquire)
}

#[cfg(target_os = "android")]
pub(crate) fn boottime_now_ns() -> i64 {
    let mut now = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    let status = unsafe { libc::clock_gettime(libc::CLOCK_BOOTTIME, &mut now) };
    if status == 0 {
        now.tv_sec
            .saturating_mul(1_000_000_000)
            .saturating_add(now.tv_nsec)
    } else {
        0
    }
}

#[cfg(not(target_os = "android"))]
pub(crate) fn boottime_now_ns() -> i64 {
    0
}

#[cfg(target_os = "android")]
#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdateCameraLatencyDiagnostics(
    _env: *mut jni::sys::JNIEnv,
    _thiz: jni::sys::jobject,
    enabled: jni::sys::jboolean,
    revision: jni::sys::jlong,
    pose_mode: jni::sys::jint,
    frame_wait_ms: jni::sys::jint,
    summary_interval_ms: jni::sys::jint,
    frame_log: jni::sys::jboolean,
    present_mode: jni::sys::jint,
    image_count: jni::sys::jint,
    capture_fps: jni::sys::jint,
    camera_sync_mode: jni::sys::jint,
    capture_processing: jni::sys::jint,
    adoption_cadence: jni::sys::jint,
    stereo_policy: jni::sys::jint,
    isolation_mode: jni::sys::jint,
    freeze_frame: jni::sys::jboolean,
    reprojection_mode: jni::sys::jint,
    assumed_capture_age_ms: jni::sys::jint,
    reprojection_fov_degrees: jni::sys::jint,
) -> i64 {
    let settings = CameraLatencySettings {
        enabled: enabled != 0,
        revision: revision.max(0) as u64,
        pose_mode: CameraLatencyPoseMode::from_code(pose_mode.max(0) as u32),
        frame_wait_ms: frame_wait_ms.clamp(0, 10) as u32,
        summary_interval_ms: summary_interval_ms.clamp(250, 5000) as u32,
        frame_log: frame_log != 0,
        present_mode: CameraLatencyPresentModePreference::from_code(present_mode.max(0) as u32),
        image_count: CameraLatencyImageCountPreference::from_code(image_count.max(0) as u32),
        capture_fps: CameraLatencyCaptureFpsPreference::from_code(capture_fps.max(0) as u32),
        camera_sync_mode: CameraLatencyCameraSyncMode::from_code(camera_sync_mode.max(0) as u32),
        capture_processing: CameraLatencyCaptureProcessing::from_code(
            capture_processing.max(0) as u32
        ),
        adoption_cadence: CameraLatencyAdoptionCadence::from_code(adoption_cadence.max(0) as u32),
        stereo_policy: CameraLatencyStereoPolicy::from_code(stereo_policy.max(0) as u32),
        isolation_mode: CameraLatencyIsolationMode::from_code(isolation_mode.max(0) as u32),
        freeze_frame: freeze_frame != 0,
        reprojection_mode: CameraLatencyReprojectionMode::from_code(reprojection_mode.max(0) as u32),
        assumed_capture_age_ms: assumed_capture_age_ms.clamp(0, 120) as u32,
        reprojection_fov_degrees: reprojection_fov_degrees.clamp(60, 130) as u32,
    };
    CAMERA_LATENCY_ENABLED.store(settings.enabled, Ordering::Release);
    CAMERA_LATENCY_POSE_MODE.store(settings.pose_mode as u32, Ordering::Release);
    CAMERA_LATENCY_FRAME_WAIT_MS.store(settings.frame_wait_ms, Ordering::Release);
    CAMERA_LATENCY_SUMMARY_INTERVAL_MS.store(settings.summary_interval_ms, Ordering::Release);
    CAMERA_LATENCY_FRAME_LOG.store(settings.frame_log, Ordering::Release);
    CAMERA_LATENCY_PRESENT_MODE.store(settings.present_mode as u32, Ordering::Release);
    CAMERA_LATENCY_IMAGE_COUNT.store(settings.image_count as u32, Ordering::Release);
    CAMERA_LATENCY_CAPTURE_FPS.store(settings.capture_fps as u32, Ordering::Release);
    CAMERA_LATENCY_CAMERA_SYNC_MODE.store(settings.camera_sync_mode as u32, Ordering::Release);
    CAMERA_LATENCY_CAPTURE_PROCESSING.store(settings.capture_processing as u32, Ordering::Release);
    CAMERA_LATENCY_ADOPTION_CADENCE.store(settings.adoption_cadence as u32, Ordering::Release);
    CAMERA_LATENCY_STEREO_POLICY.store(settings.stereo_policy as u32, Ordering::Release);
    CAMERA_LATENCY_ISOLATION_MODE.store(settings.isolation_mode as u32, Ordering::Release);
    CAMERA_LATENCY_FREEZE_FRAME.store(settings.freeze_frame, Ordering::Release);
    CAMERA_LATENCY_REPROJECTION_MODE.store(settings.reprojection_mode as u32, Ordering::Release);
    CAMERA_LATENCY_ASSUMED_CAPTURE_AGE_MS.store(settings.assumed_capture_age_ms, Ordering::Release);
    CAMERA_LATENCY_REPROJECTION_FOV_DEGREES
        .store(settings.reprojection_fov_degrees, Ordering::Release);
    CAMERA_LATENCY_REVISION.store(settings.revision, Ordering::Release);
    log_marker(format!(
        "status=latency-hotload-applied transport=jni-revision-last liveSafeFields=pose-mode,frame-wait-ms,summary-interval-ms,frame-log,camera-sync-mode,adoption-cadence,stereo-policy,isolation-mode,freeze-frame,reprojection-mode,assumed-capture-age-ms,reprojection-fov-degrees restartRequiredFields=present-mode,image-count,capture-fps,capture-processing {} dynamicCameraPoseMetadataUsed=false imageTimestampPoseAssociation=selected-by-camera-latency-reprojection-mode",
        settings.marker_fields(),
    ));
    0xff
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct CameraLatencyViewerBasis {
    pub(crate) timestamp_ns: i64,
    pub(crate) right: [f32; 3],
    pub(crate) up: [f32; 3],
    pub(crate) forward: [f32; 3],
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct CameraLatencyCaptureViewerBasis {
    pub(crate) basis: Option<CameraLatencyViewerBasis>,
    pub(crate) target_timestamp_ns: i64,
    pub(crate) association: &'static str,
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct CameraLatencyRotationReprojection {
    pub(crate) row0: [f32; 4],
    pub(crate) row1: [f32; 4],
    pub(crate) row2: [f32; 4],
    pub(crate) params: [f32; 4],
}

impl CameraLatencyRotationReprojection {
    pub(crate) fn disabled() -> Self {
        Self {
            row0: [1.0, 0.0, 0.0, 0.5],
            row1: [0.0, 1.0, 0.0, 0.5],
            row2: [0.0, 0.0, 1.0, 0.0],
            params: [0.0, 1.0, 1.0, 0.0],
        }
    }
}

#[derive(Clone, Copy, Debug)]
struct CameraLatencyCameraCalibration {
    sensor_to_camera: [[f32; 3]; 3],
    tan_half_horizontal_fov: f32,
    tan_half_vertical_fov: f32,
    principal_u: f32,
    principal_v: f32,
}

static CAMERA_LATENCY_VIEWER_HISTORY: OnceLock<Mutex<VecDeque<CameraLatencyViewerBasis>>> =
    OnceLock::new();
static CAMERA_LATENCY_CAMERA_CALIBRATION: OnceLock<Mutex<Option<CameraLatencyCameraCalibration>>> =
    OnceLock::new();

fn viewer_history() -> &'static Mutex<VecDeque<CameraLatencyViewerBasis>> {
    CAMERA_LATENCY_VIEWER_HISTORY.get_or_init(|| Mutex::new(VecDeque::with_capacity(128)))
}

fn camera_calibration() -> &'static Mutex<Option<CameraLatencyCameraCalibration>> {
    CAMERA_LATENCY_CAMERA_CALIBRATION.get_or_init(|| Mutex::new(None))
}

fn normalize3(value: [f32; 3]) -> Option<[f32; 3]> {
    if value.iter().any(|component| !component.is_finite()) {
        return None;
    }
    let length = (value[0] * value[0] + value[1] * value[1] + value[2] * value[2]).sqrt();
    if length < 0.0001 {
        return None;
    }
    Some([value[0] / length, value[1] / length, value[2] / length])
}

fn dot3(left: [f32; 3], right: [f32; 3]) -> f32 {
    left[0] * right[0] + left[1] * right[1] + left[2] * right[2]
}

fn transpose3(matrix: [[f32; 3]; 3]) -> [[f32; 3]; 3] {
    [
        [matrix[0][0], matrix[1][0], matrix[2][0]],
        [matrix[0][1], matrix[1][1], matrix[2][1]],
        [matrix[0][2], matrix[1][2], matrix[2][2]],
    ]
}

fn multiply3(left: [[f32; 3]; 3], right: [[f32; 3]; 3]) -> [[f32; 3]; 3] {
    let mut result = [[0.0; 3]; 3];
    for row in 0..3 {
        for column in 0..3 {
            result[row][column] = left[row][0] * right[0][column]
                + left[row][1] * right[1][column]
                + left[row][2] * right[2][column];
        }
    }
    result
}

fn quaternion_rotation_matrix(quaternion: [f32; 4]) -> Option<[[f32; 3]; 3]> {
    if quaternion.iter().any(|value| !value.is_finite()) {
        return None;
    }
    let length_squared = quaternion.iter().map(|value| value * value).sum::<f32>();
    if length_squared < 0.0001 {
        return None;
    }
    let inverse_length = length_squared.sqrt().recip();
    let x = quaternion[0] * inverse_length;
    let y = quaternion[1] * inverse_length;
    let z = quaternion[2] * inverse_length;
    let w = quaternion[3] * inverse_length;
    Some([
        [
            1.0 - 2.0 * y * y - 2.0 * z * z,
            2.0 * x * y - 2.0 * z * w,
            2.0 * x * z + 2.0 * y * w,
        ],
        [
            2.0 * x * y + 2.0 * z * w,
            1.0 - 2.0 * x * x - 2.0 * z * z,
            2.0 * y * z - 2.0 * x * w,
        ],
        [
            2.0 * x * z - 2.0 * y * w,
            2.0 * y * z + 2.0 * x * w,
            1.0 - 2.0 * x * x - 2.0 * y * y,
        ],
    ])
}

pub(crate) fn update_camera_latency_camera_calibration(
    camera_id: &str,
    lens_pose_rotation: &[f32],
    lens_intrinsic_calibration: &[f32],
    lens_pose_reference: Option<u8>,
    pre_correction_active_array: &[i32],
    selected_size: [i32; 2],
) -> bool {
    let calibration = (|| {
        if lens_pose_reference != Some(1)
            || lens_pose_rotation.len() < 4
            || lens_intrinsic_calibration.len() < 5
            || pre_correction_active_array.len() < 4
        {
            return None;
        }
        let active_left = pre_correction_active_array[0];
        let active_top = pre_correction_active_array[1];
        let active_width = pre_correction_active_array[2].saturating_sub(active_left);
        let active_height = pre_correction_active_array[3].saturating_sub(active_top);
        if active_width <= 0 || active_height <= 0 || selected_size != [active_width, active_height]
        {
            return None;
        }
        let fx = lens_intrinsic_calibration[0];
        let fy = lens_intrinsic_calibration[1];
        let cx = lens_intrinsic_calibration[2];
        let cy = lens_intrinsic_calibration[3];
        if !fx.is_finite()
            || !fy.is_finite()
            || !cx.is_finite()
            || !cy.is_finite()
            || fx <= 0.0
            || fy <= 0.0
        {
            return None;
        }
        let sensor_to_camera = quaternion_rotation_matrix([
            lens_pose_rotation[0],
            lens_pose_rotation[1],
            lens_pose_rotation[2],
            lens_pose_rotation[3],
        ])?;
        let width = active_width as f32;
        let height = active_height as f32;
        let principal_u = (cx - active_left as f32) / width;
        let principal_v = (cy - active_top as f32) / height;
        if !(0.0..=1.0).contains(&principal_u) || !(0.0..=1.0).contains(&principal_v) {
            return None;
        }
        Some(CameraLatencyCameraCalibration {
            sensor_to_camera,
            tan_half_horizontal_fov: width / (2.0 * fx),
            tan_half_vertical_fov: height / (2.0 * fy),
            principal_u,
            principal_v,
        })
    })();
    let available = calibration.is_some();
    if let Ok(mut current) = camera_calibration().lock() {
        *current = calibration;
    }
    if let Some(calibration) = calibration {
        log_marker(format!(
            "status=camera-latency-camera-calibration-updated cameraId={} calibrationSource=android-camera2-static-characteristics calibrationScope=shared-left-camera-approximation lensPoseReference=gyroscope selectedSize={}x{} tanHalfHorizontalFov={:.6} tanHalfVerticalFov={:.6} horizontalFovDegrees={:.3} verticalFovDegrees={:.3} principalU={:.6} principalV={:.6} cameraExtrinsicApplied=true intrinsicsApplied=true",
            camera_id,
            selected_size[0],
            selected_size[1],
            calibration.tan_half_horizontal_fov,
            calibration.tan_half_vertical_fov,
            2.0 * calibration.tan_half_horizontal_fov.atan().to_degrees(),
            2.0 * calibration.tan_half_vertical_fov.atan().to_degrees(),
            calibration.principal_u,
            calibration.principal_v,
        ));
    } else {
        log_marker(format!(
            "status=camera-latency-camera-calibration-unavailable cameraId={} calibrationSource=android-camera2-static-characteristics lensPoseReference={} lensPoseRotationCount={} intrinsicCalibrationCount={} preCorrectionActiveArrayCount={} selectedSize={}x{} calibratedReprojectionFallback=disabled",
            camera_id,
            lens_pose_reference
                .map(|value| value.to_string())
                .unwrap_or_else(|| "unavailable".to_string()),
            lens_pose_rotation.len(),
            lens_intrinsic_calibration.len(),
            pre_correction_active_array.len(),
            selected_size[0],
            selected_size[1],
        ));
    }
    available
}

fn cross3(left: [f32; 3], right: [f32; 3]) -> [f32; 3] {
    [
        left[1] * right[2] - left[2] * right[1],
        left[2] * right[0] - left[0] * right[2],
        left[0] * right[1] - left[1] * right[0],
    ]
}

fn filter_viewer_basis(
    basis: CameraLatencyViewerBasis,
    filter: CameraLatencyRotationAxisFilter,
) -> Option<CameraLatencyViewerBasis> {
    if filter == CameraLatencyRotationAxisFilter::Full {
        return Some(basis);
    }
    let forward = if filter == CameraLatencyRotationAxisFilter::YawOnly {
        normalize3([basis.forward[0], 0.0, basis.forward[2]])?
    } else {
        normalize3(basis.forward)?
    };
    let world_up = [0.0, 1.0, 0.0];
    let right = normalize3(cross3(forward, world_up))?;
    let up = normalize3(cross3(right, forward))?;
    Some(CameraLatencyViewerBasis {
        timestamp_ns: basis.timestamp_ns,
        right,
        up,
        forward,
    })
}

pub(crate) fn record_camera_latency_viewer_basis(
    timestamp_ns: i64,
    right: [f32; 3],
    up: [f32; 3],
    forward: [f32; 3],
) -> bool {
    if timestamp_ns <= 0 {
        return false;
    }
    let Some(right) = normalize3(right) else {
        return false;
    };
    let Some(up) = normalize3(up) else {
        return false;
    };
    let Some(forward) = normalize3(forward) else {
        return false;
    };
    let Ok(mut history) = viewer_history().lock() else {
        return false;
    };
    if history
        .back()
        .is_some_and(|sample| sample.timestamp_ns >= timestamp_ns)
    {
        return false;
    }
    history.push_back(CameraLatencyViewerBasis {
        timestamp_ns,
        right,
        up,
        forward,
    });
    while history.len() > 128
        || history
            .front()
            .is_some_and(|sample| timestamp_ns.saturating_sub(sample.timestamp_ns) > 1_000_000_000)
    {
        history.pop_front();
    }
    true
}

pub(crate) fn camera_latency_capture_viewer_basis(
    sensor_timestamp_ns: i64,
    callback_boottime_ns: i64,
) -> CameraLatencyCaptureViewerBasis {
    let settings = current_camera_latency_settings();
    if !settings.enabled || !settings.reprojection_mode.rotation_enabled() {
        return CameraLatencyCaptureViewerBasis {
            basis: None,
            target_timestamp_ns: 0,
            association: "disabled",
        };
    }
    let direct_sensor_delta_ns = callback_boottime_ns.saturating_sub(sensor_timestamp_ns);
    let sensor_timestamp_plausible = sensor_timestamp_ns > 0
        && callback_boottime_ns >= sensor_timestamp_ns
        && direct_sensor_delta_ns <= 250_000_000;
    let (target_ns, association) =
        if settings.reprojection_mode.uses_sensor_timestamp() && sensor_timestamp_plausible {
            (
                sensor_timestamp_ns,
                "sensor-timestamp-direct-plausible-boottime-delta",
            )
        } else {
            (
                callback_boottime_ns
                    .saturating_sub(i64::from(settings.assumed_capture_age_ms) * 1_000_000),
                if settings.reprojection_mode.uses_sensor_timestamp() {
                    "sensor-timestamp-fallback-callback-minus-assumed-age"
                } else {
                    "callback-minus-assumed-age"
                },
            )
        };
    let basis = viewer_history().lock().ok().and_then(|history| {
        history
            .iter()
            .rev()
            .copied()
            .find(|sample| sample.timestamp_ns <= target_ns)
            .or_else(|| history.front().copied())
    });
    CameraLatencyCaptureViewerBasis {
        basis,
        target_timestamp_ns: target_ns,
        association,
    }
}

pub(crate) fn current_camera_latency_rotation_reprojection(
    capture: Option<CameraLatencyViewerBasis>,
) -> CameraLatencyRotationReprojection {
    let settings = current_camera_latency_settings();
    if !settings.enabled || !settings.reprojection_mode.rotation_enabled() {
        return CameraLatencyRotationReprojection::disabled();
    }
    let Some(capture) = capture else {
        return CameraLatencyRotationReprojection::disabled();
    };
    let current = viewer_history()
        .lock()
        .ok()
        .and_then(|history| history.back().copied());
    let Some(current) = current else {
        return CameraLatencyRotationReprojection::disabled();
    };
    let axis_filter = settings.reprojection_mode.axis_filter();
    let Some(capture) = filter_viewer_basis(capture, axis_filter) else {
        return CameraLatencyRotationReprojection::disabled();
    };
    let Some(current) = filter_viewer_basis(current, axis_filter) else {
        return CameraLatencyRotationReprojection::disabled();
    };
    let camera_calibration = if settings.reprojection_mode.uses_camera_calibration() {
        camera_calibration()
            .lock()
            .ok()
            .and_then(|calibration| *calibration)
    } else {
        None
    };
    if settings.reprojection_mode.uses_camera_calibration() && camera_calibration.is_none() {
        return CameraLatencyRotationReprojection::disabled();
    }
    let half_fov_radians = (settings.reprojection_fov_degrees as f32).to_radians() * 0.5;
    let configured_tangent = half_fov_radians.tan().max(0.01);
    let capture_from_current = [
        [
            dot3(capture.right, current.right),
            dot3(capture.right, current.up),
            dot3(capture.right, current.forward),
        ],
        [
            dot3(capture.up, current.right),
            dot3(capture.up, current.up),
            dot3(capture.up, current.forward),
        ],
        [
            dot3(capture.forward, current.right),
            dot3(capture.forward, current.up),
            dot3(capture.forward, current.forward),
        ],
    ];
    let rotation = if let Some(calibration) = camera_calibration {
        multiply3(
            multiply3(calibration.sensor_to_camera, capture_from_current),
            transpose3(calibration.sensor_to_camera),
        )
    } else if settings.reprojection_mode.inverse_direction() {
        [
            [
                capture_from_current[0][0],
                capture_from_current[1][0],
                capture_from_current[2][0],
            ],
            [
                capture_from_current[0][1],
                capture_from_current[1][1],
                capture_from_current[2][1],
            ],
            [
                capture_from_current[0][2],
                capture_from_current[1][2],
                capture_from_current[2][2],
            ],
        ]
    } else {
        capture_from_current
    };
    let (tan_half_horizontal_fov, tan_half_vertical_fov, principal_u, principal_v) =
        camera_calibration
            .map(|calibration| {
                (
                    calibration.tan_half_horizontal_fov,
                    calibration.tan_half_vertical_fov,
                    calibration.principal_u,
                    calibration.principal_v,
                )
            })
            .unwrap_or((configured_tangent, configured_tangent, 0.5, 0.5));
    CameraLatencyRotationReprojection {
        row0: [rotation[0][0], rotation[0][1], rotation[0][2], principal_u],
        row1: [rotation[1][0], rotation[1][1], rotation[1][2], principal_v],
        row2: [rotation[2][0], rotation[2][1], rotation[2][2], 0.0],
        params: [
            1.0,
            tan_half_horizontal_fov,
            tan_half_vertical_fov,
            current.timestamp_ns.saturating_sub(capture.timestamp_ns) as f32 / 1_000_000.0,
        ],
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
#[allow(non_snake_case, clippy::too_many_arguments)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdateCameraLatencyViewerPose(
    _env: *mut jni::sys::JNIEnv,
    _thiz: jni::sys::jobject,
    timestamp_ns: jni::sys::jlong,
    right_x: jni::sys::jfloat,
    right_y: jni::sys::jfloat,
    right_z: jni::sys::jfloat,
    up_x: jni::sys::jfloat,
    up_y: jni::sys::jfloat,
    up_z: jni::sys::jfloat,
    forward_x: jni::sys::jfloat,
    forward_y: jni::sys::jfloat,
    forward_z: jni::sys::jfloat,
) -> i64 {
    if record_camera_latency_viewer_basis(
        timestamp_ns,
        [right_x, right_y, right_z],
        [up_x, up_y, up_z],
        [forward_x, forward_y, forward_z],
    ) {
        0xff
    } else {
        0
    }
}

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct CameraLatencyFrameTiming {
    pub(crate) fence_wait: Duration,
    pub(crate) camera_wait: Duration,
    pub(crate) camera_import: Duration,
    pub(crate) acquire_swapchain: Duration,
    pub(crate) record: Duration,
    pub(crate) submit: Duration,
    pub(crate) present_call: Duration,
    pub(crate) loop_total: Duration,
}

#[derive(Clone, Copy, Debug, Default)]
struct DurationAggregate {
    total_ns: u128,
    max_ns: u128,
}

#[derive(Clone, Copy, Debug, Default)]
struct IntervalAggregate {
    samples: u64,
    total_ns: u128,
    min_ns: u64,
    max_ns: u64,
}

impl IntervalAggregate {
    fn add(&mut self, value_ns: Option<u64>) {
        let Some(value_ns) = value_ns.filter(|value| *value > 0) else {
            return;
        };
        self.samples = self.samples.saturating_add(1);
        self.total_ns = self.total_ns.saturating_add(value_ns as u128);
        self.min_ns = if self.min_ns == 0 {
            value_ns
        } else {
            self.min_ns.min(value_ns)
        };
        self.max_ns = self.max_ns.max(value_ns);
    }

    fn avg_ms(self) -> String {
        if self.samples == 0 {
            "unavailable".to_string()
        } else {
            format!(
                "{:.3}",
                self.total_ns as f64 / self.samples as f64 / 1_000_000.0
            )
        }
    }

    fn min_ms(self) -> String {
        if self.samples == 0 {
            "unavailable".to_string()
        } else {
            format!("{:.3}", self.min_ns as f64 / 1_000_000.0)
        }
    }

    fn max_ms(self) -> String {
        if self.samples == 0 {
            "unavailable".to_string()
        } else {
            format!("{:.3}", self.max_ns as f64 / 1_000_000.0)
        }
    }
}

#[derive(Clone, Copy, Debug, Default)]
struct FrameCadenceAggregate {
    last_imported_frame_index: u64,
    source_interval: IntervalAggregate,
    callback_interval: IntervalAggregate,
    skipped_source_frames: u64,
    held_frame_index: u64,
    held_presentations: u64,
    completed_holds: u64,
    total_hold_presentations: u64,
    hold_one: u64,
    hold_two: u64,
    hold_three: u64,
    hold_four_plus: u64,
}

impl FrameCadenceAggregate {
    fn new(frame_index: u64) -> Self {
        Self {
            last_imported_frame_index: frame_index,
            held_frame_index: frame_index,
            ..Self::default()
        }
    }

    fn record(
        &mut self,
        frame_index: u64,
        imported: bool,
        source_delta_ns: Option<u64>,
        callback_delta_ns: Option<u64>,
    ) {
        if frame_index == self.held_frame_index {
            self.held_presentations = self.held_presentations.saturating_add(1);
        } else {
            self.finalize_hold();
            self.held_frame_index = frame_index;
            self.held_presentations = 1;
        }
        if !imported {
            return;
        }
        if self.last_imported_frame_index > 0 {
            self.skipped_source_frames = self.skipped_source_frames.saturating_add(
                frame_index.saturating_sub(self.last_imported_frame_index.saturating_add(1)),
            );
        }
        self.last_imported_frame_index = frame_index;
        self.source_interval.add(source_delta_ns);
        self.callback_interval.add(callback_delta_ns);
    }

    fn finalize_hold(&mut self) {
        if self.held_presentations == 0 {
            return;
        }
        self.completed_holds = self.completed_holds.saturating_add(1);
        self.total_hold_presentations = self
            .total_hold_presentations
            .saturating_add(self.held_presentations);
        match self.held_presentations {
            1 => self.hold_one = self.hold_one.saturating_add(1),
            2 => self.hold_two = self.hold_two.saturating_add(1),
            3 => self.hold_three = self.hold_three.saturating_add(1),
            _ => self.hold_four_plus = self.hold_four_plus.saturating_add(1),
        }
        self.held_presentations = 0;
    }

    fn finalized(mut self) -> Self {
        self.finalize_hold();
        self
    }

    fn average_hold_frames(self) -> f64 {
        if self.completed_holds == 0 {
            0.0
        } else {
            self.total_hold_presentations as f64 / self.completed_holds as f64
        }
    }
}

impl DurationAggregate {
    fn add(&mut self, value: Duration) {
        let value_ns = value.as_nanos();
        self.total_ns = self.total_ns.saturating_add(value_ns);
        self.max_ns = self.max_ns.max(value_ns);
    }

    fn avg_ms(self, samples: u64) -> f64 {
        if samples == 0 {
            0.0
        } else {
            self.total_ns as f64 / samples as f64 / 1_000_000.0
        }
    }

    fn max_ms(self) -> f64 {
        self.max_ns as f64 / 1_000_000.0
    }
}

pub(crate) struct CameraLatencyWindow {
    started: Instant,
    samples: u64,
    left_imports: u64,
    right_imports: u64,
    both_eye_import_presents: u64,
    left_only_import_presents: u64,
    right_only_import_presents: u64,
    held_pair_presents: u64,
    camera_projection_visible_presents: u64,
    camera_projection_suppressed_presents: u64,
    start_left_frame_index: u64,
    start_right_frame_index: u64,
    latest_left_frame_index: u64,
    latest_right_frame_index: u64,
    start_left_published_frame_count: u64,
    start_right_published_frame_count: u64,
    latest_left_published_frame_count: u64,
    latest_right_published_frame_count: u64,
    fence_wait: DurationAggregate,
    camera_wait: DurationAggregate,
    camera_import: DurationAggregate,
    acquire_swapchain: DurationAggregate,
    record: DurationAggregate,
    submit: DurationAggregate,
    present_call: DurationAggregate,
    loop_total: DurationAggregate,
    left_cadence: FrameCadenceAggregate,
    right_cadence: FrameCadenceAggregate,
}

impl CameraLatencyWindow {
    pub(crate) fn new(
        left_frame_index: u64,
        right_frame_index: u64,
        left_published_frame_count: u64,
        right_published_frame_count: u64,
    ) -> Self {
        Self {
            started: Instant::now(),
            samples: 0,
            left_imports: 0,
            right_imports: 0,
            both_eye_import_presents: 0,
            left_only_import_presents: 0,
            right_only_import_presents: 0,
            held_pair_presents: 0,
            camera_projection_visible_presents: 0,
            camera_projection_suppressed_presents: 0,
            start_left_frame_index: left_frame_index,
            start_right_frame_index: right_frame_index,
            latest_left_frame_index: left_frame_index,
            latest_right_frame_index: right_frame_index,
            start_left_published_frame_count: left_published_frame_count,
            start_right_published_frame_count: right_published_frame_count,
            latest_left_published_frame_count: left_published_frame_count,
            latest_right_published_frame_count: right_published_frame_count,
            fence_wait: DurationAggregate::default(),
            camera_wait: DurationAggregate::default(),
            camera_import: DurationAggregate::default(),
            acquire_swapchain: DurationAggregate::default(),
            record: DurationAggregate::default(),
            submit: DurationAggregate::default(),
            present_call: DurationAggregate::default(),
            loop_total: DurationAggregate::default(),
            left_cadence: FrameCadenceAggregate::new(left_frame_index),
            right_cadence: FrameCadenceAggregate::new(right_frame_index),
        }
    }

    pub(crate) fn record(
        &mut self,
        timing: CameraLatencyFrameTiming,
        left_imported: bool,
        right_imported: bool,
        left_frame_index: u64,
        right_frame_index: u64,
        left_published_frame_count: u64,
        right_published_frame_count: u64,
        left_source_delta_ns: Option<u64>,
        right_source_delta_ns: Option<u64>,
        left_callback_delta_ns: Option<u64>,
        right_callback_delta_ns: Option<u64>,
        camera_projection_visible: bool,
    ) {
        self.samples = self.samples.saturating_add(1);
        self.left_imports = self.left_imports.saturating_add(u64::from(left_imported));
        self.right_imports = self.right_imports.saturating_add(u64::from(right_imported));
        match (left_imported, right_imported) {
            (true, true) => {
                self.both_eye_import_presents = self.both_eye_import_presents.saturating_add(1)
            }
            (true, false) => {
                self.left_only_import_presents = self.left_only_import_presents.saturating_add(1)
            }
            (false, true) => {
                self.right_only_import_presents = self.right_only_import_presents.saturating_add(1)
            }
            (false, false) => self.held_pair_presents = self.held_pair_presents.saturating_add(1),
        }
        self.camera_projection_visible_presents = self
            .camera_projection_visible_presents
            .saturating_add(u64::from(camera_projection_visible));
        self.camera_projection_suppressed_presents = self
            .camera_projection_suppressed_presents
            .saturating_add(u64::from(!camera_projection_visible));
        self.latest_left_frame_index = left_frame_index;
        self.latest_right_frame_index = right_frame_index;
        self.latest_left_published_frame_count = left_published_frame_count;
        self.latest_right_published_frame_count = right_published_frame_count;
        self.fence_wait.add(timing.fence_wait);
        self.camera_wait.add(timing.camera_wait);
        self.camera_import.add(timing.camera_import);
        self.acquire_swapchain.add(timing.acquire_swapchain);
        self.record.add(timing.record);
        self.submit.add(timing.submit);
        self.present_call.add(timing.present_call);
        self.loop_total.add(timing.loop_total);
        self.left_cadence.record(
            left_frame_index,
            left_imported,
            left_source_delta_ns,
            left_callback_delta_ns,
        );
        self.right_cadence.record(
            right_frame_index,
            right_imported,
            right_source_delta_ns,
            right_callback_delta_ns,
        );
    }

    pub(crate) fn should_emit(&self, settings: CameraLatencySettings) -> bool {
        settings.enabled
            && self.started.elapsed()
                >= Duration::from_millis(settings.summary_interval_ms.clamp(250, 5000) as u64)
    }

    #[allow(clippy::too_many_arguments)]
    pub(crate) fn emit_and_reset(
        &mut self,
        settings: CameraLatencySettings,
        active_present_mode: vk::PresentModeKHR,
        active_image_count: u32,
        active_launch_settings: CameraLatencySettings,
        left_timestamp_source: &str,
        right_timestamp_source: &str,
        left_callback_age_ns: Option<u64>,
        right_callback_age_ns: Option<u64>,
        left_present_call_age_ns: Option<u64>,
        right_present_call_age_ns: Option<u64>,
        stereo_pair_delta_ns: u64,
    ) {
        let elapsed = self.started.elapsed();
        let elapsed_ms = elapsed.as_secs_f64() * 1000.0;
        let samples = self.samples.max(1);
        let fps = if elapsed.as_secs_f64() > 0.0 {
            self.samples as f64 / elapsed.as_secs_f64()
        } else {
            0.0
        };
        let launch_settings_pending_restart = settings.present_mode
            != active_launch_settings.present_mode
            || settings.image_count != active_launch_settings.image_count
            || settings.capture_fps != active_launch_settings.capture_fps
            || settings.capture_processing != active_launch_settings.capture_processing;
        let left_cadence = self.left_cadence.finalized();
        let right_cadence = self.right_cadence.finalized();
        let strict_pair_selected =
            settings.stereo_policy == CameraLatencyStereoPolicy::StrictTimestampPair;
        let strict_atomic_import_invariant = !strict_pair_selected
            || (self.left_only_import_presents == 0 && self.right_only_import_presents == 0);
        let strict_pair_delta_within_limit = !strict_pair_selected
            || stereo_pair_delta_ns <= CAMERA_LATENCY_STRICT_PAIR_MAX_DELTA_NS;
        log_marker(format!(
            "status=latency-stereo-summary windowMs={:.3} presentedFrames={} stereoPolicy={} bothEyeImportPresents={} leftOnlyImportPresents={} rightOnlyImportPresents={} heldPairPresents={} currentPairDeltaMs={:.3} strictPairMaxDeltaMs={:.3} strictPairDeltaWithinLimit={} strictAtomicImportInvariant={} packedStereoSurface=true bothEyesRecordedInSingleCommandBuffer=true singleQueuePresentPerSurfaceFrame=true",
            elapsed_ms,
            self.samples,
            settings.stereo_policy.marker_token(),
            self.both_eye_import_presents,
            self.left_only_import_presents,
            self.right_only_import_presents,
            self.held_pair_presents,
            stereo_pair_delta_ns as f64 / 1_000_000.0,
            CAMERA_LATENCY_STRICT_PAIR_MAX_DELTA_NS as f64 / 1_000_000.0,
            bool_token(strict_pair_delta_within_limit),
            bool_token(strict_atomic_import_invariant),
        ));
        log_marker(format!(
            "status=latency-summary windowMs={:.3} renderFps={:.3} presentedFrames={} leftAcquiredCallbacks={} rightAcquiredCallbacks={} callbackCounterSemantics=successfully-published-camera-frame leftImportedFrames={} rightImportedFrames={} cameraProjectionVisiblePresents={} cameraProjectionSuppressedPresents={} cameraProjectionVisibilitySemantics=custom-projection-only-video-preserved leftSourceIntervalAvgMs={} leftSourceIntervalMinMs={} leftSourceIntervalMaxMs={} rightSourceIntervalAvgMs={} rightSourceIntervalMinMs={} rightSourceIntervalMaxMs={} leftCallbackIntervalAvgMs={} leftCallbackIntervalMinMs={} leftCallbackIntervalMaxMs={} rightCallbackIntervalAvgMs={} rightCallbackIntervalMinMs={} rightCallbackIntervalMaxMs={} leftDisplayHoldAvgFrames={:.3} rightDisplayHoldAvgFrames={:.3} leftHoldHistogram1_2_3_4plus={};{};{};{} rightHoldHistogram1_2_3_4plus={};{};{};{} leftSkippedSourceFrames={} rightSkippedSourceFrames={} leftCallbackAgeMs={} rightCallbackAgeMs={} leftSensorToPresentCallAgeMs={} rightSensorToPresentCallAgeMs={} stereoPairDeltaMs={:.3} fenceWaitAvgMs={:.3} fenceWaitMaxMs={:.3} cameraWaitAvgMs={:.3} cameraWaitMaxMs={:.3} cameraImportAvgMs={:.3} cameraImportMaxMs={:.3} acquireSwapchainAvgMs={:.3} acquireSwapchainMaxMs={:.3} recordAvgMs={:.3} recordMaxMs={:.3} submitAvgMs={:.3} submitMaxMs={:.3} presentCallAvgMs={:.3} presentCallMaxMs={:.3} loopAvgMs={:.3} loopMaxMs={:.3} leftTimestampSource={} rightTimestampSource={} sourceTimestampIntervalSemantics=relative-valid-even-when-absolute-age-unavailable activePresentMode={:?} activeSwapchainImages={} launchSettingsPendingRestart={} dynamicCameraPoseMetadataUsed=false imageTimestampPoseAssociation=selected-by-camera-latency-reprojection-mode captureResultMetadataCallbacks=false presentAgeSemantics=queue-present-call-not-photons {}",
            elapsed_ms,
            fps,
            self.samples,
            self.latest_left_published_frame_count
                .saturating_sub(self.start_left_published_frame_count),
            self.latest_right_published_frame_count
                .saturating_sub(self.start_right_published_frame_count),
            self.left_imports,
            self.right_imports,
            self.camera_projection_visible_presents,
            self.camera_projection_suppressed_presents,
            left_cadence.source_interval.avg_ms(),
            left_cadence.source_interval.min_ms(),
            left_cadence.source_interval.max_ms(),
            right_cadence.source_interval.avg_ms(),
            right_cadence.source_interval.min_ms(),
            right_cadence.source_interval.max_ms(),
            left_cadence.callback_interval.avg_ms(),
            left_cadence.callback_interval.min_ms(),
            left_cadence.callback_interval.max_ms(),
            right_cadence.callback_interval.avg_ms(),
            right_cadence.callback_interval.min_ms(),
            right_cadence.callback_interval.max_ms(),
            left_cadence.average_hold_frames(),
            right_cadence.average_hold_frames(),
            left_cadence.hold_one,
            left_cadence.hold_two,
            left_cadence.hold_three,
            left_cadence.hold_four_plus,
            right_cadence.hold_one,
            right_cadence.hold_two,
            right_cadence.hold_three,
            right_cadence.hold_four_plus,
            left_cadence.skipped_source_frames,
            right_cadence.skipped_source_frames,
            optional_ns_ms(left_callback_age_ns),
            optional_ns_ms(right_callback_age_ns),
            optional_ns_ms(left_present_call_age_ns),
            optional_ns_ms(right_present_call_age_ns),
            stereo_pair_delta_ns as f64 / 1_000_000.0,
            self.fence_wait.avg_ms(samples),
            self.fence_wait.max_ms(),
            self.camera_wait.avg_ms(samples),
            self.camera_wait.max_ms(),
            self.camera_import.avg_ms(samples),
            self.camera_import.max_ms(),
            self.acquire_swapchain.avg_ms(samples),
            self.acquire_swapchain.max_ms(),
            self.record.avg_ms(samples),
            self.record.max_ms(),
            self.submit.avg_ms(samples),
            self.submit.max_ms(),
            self.present_call.avg_ms(samples),
            self.present_call.max_ms(),
            self.loop_total.avg_ms(samples),
            self.loop_total.max_ms(),
            left_timestamp_source,
            right_timestamp_source,
            active_present_mode,
            active_image_count,
            bool_token(launch_settings_pending_restart),
            settings.marker_fields(),
        ));
        *self = Self::new(
            self.latest_left_frame_index,
            self.latest_right_frame_index,
            self.latest_left_published_frame_count,
            self.latest_right_published_frame_count,
        );
    }
}

fn optional_ns_ms(value: Option<u64>) -> String {
    value
        .map(|value| format!("{:.3}", value as f64 / 1_000_000.0))
        .unwrap_or_else(|| "unavailable".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn capabilities(min: u32, max: u32) -> vk::SurfaceCapabilitiesKHR {
        vk::SurfaceCapabilitiesKHR {
            min_image_count: min,
            max_image_count: max,
            ..Default::default()
        }
    }

    #[test]
    fn camera_latency_present_mode_preferences_fallback_closed() {
        let fifo_only = [vk::PresentModeKHR::FIFO];
        assert_eq!(
            CameraLatencyPresentModePreference::MailboxIfAvailable.choose(&fifo_only),
            vk::PresentModeKHR::FIFO
        );
        let all = [
            vk::PresentModeKHR::FIFO,
            vk::PresentModeKHR::IMMEDIATE,
            vk::PresentModeKHR::MAILBOX,
        ];
        assert_eq!(
            CameraLatencyPresentModePreference::AutoLowLatency.choose(&all),
            vk::PresentModeKHR::MAILBOX
        );
        assert_eq!(
            CameraLatencyPresentModePreference::ImmediateIfAvailable.choose(&all),
            vk::PresentModeKHR::IMMEDIATE
        );
    }

    #[test]
    fn camera_latency_image_count_preferences_are_bounded() {
        assert_eq!(
            CameraLatencyImageCountPreference::MinSafe.choose(&capabilities(2, 4)),
            2
        );
        assert_eq!(
            CameraLatencyImageCountPreference::MinPlusOne.choose(&capabilities(2, 4)),
            3
        );
        assert_eq!(
            CameraLatencyImageCountPreference::MinPlusOne.choose(&capabilities(3, 3)),
            3
        );
    }

    #[test]
    fn camera_latency_display_aligned_45_adopts_every_second_display_frame() {
        let settings = CameraLatencySettings {
            enabled: true,
            adoption_cadence: CameraLatencyAdoptionCadence::DisplayAligned45,
            ..Default::default()
        };
        assert!(settings.should_adopt_camera_image(0));
        assert!(!settings.should_adopt_camera_image(1));
        assert!(settings.should_adopt_camera_image(2));
        assert!(!settings.should_adopt_camera_image(3));

        let disabled = CameraLatencySettings {
            enabled: false,
            adoption_cadence: CameraLatencyAdoptionCadence::DisplayAligned45,
            ..Default::default()
        };
        assert!(disabled.should_adopt_camera_image(1));
    }

    #[test]
    fn camera_sync_processing_and_isolation_codes_fallback_closed() {
        assert_eq!(
            CameraLatencyCameraSyncMode::from_code(1),
            CameraLatencyCameraSyncMode::HoldImageUntilGpuFence
        );
        assert_eq!(
            CameraLatencyCameraSyncMode::from_code(99),
            CameraLatencyCameraSyncMode::EarlyDeleteAhbRetained
        );
        assert_eq!(
            CameraLatencyCaptureProcessing::from_code(1),
            CameraLatencyCaptureProcessing::NoiseEdgeOff
        );
        assert_eq!(
            CameraLatencyIsolationMode::from_code(1),
            CameraLatencyIsolationMode::OpaqueCameraOnly
        );
    }

    #[test]
    fn camera_reprojection_mode_codes_preserve_assumed_age_and_sensor_ab() {
        assert_eq!(
            CameraLatencyReprojectionMode::from_code(1),
            CameraLatencyReprojectionMode::RotationOnlyAssumedAge
        );
        assert_eq!(
            CameraLatencyReprojectionMode::from_code(2),
            CameraLatencyReprojectionMode::RotationOnlySensorTimestamp
        );
        assert_eq!(
            CameraLatencyReprojectionMode::from_code(3),
            CameraLatencyReprojectionMode::RotationOnlySensorTimestampInverse
        );
        assert_eq!(
            CameraLatencyReprojectionMode::from_code(4),
            CameraLatencyReprojectionMode::RotationOnlySensorTimestampInverseRollFree
        );
        assert_eq!(
            CameraLatencyReprojectionMode::from_code(5),
            CameraLatencyReprojectionMode::RotationOnlySensorTimestampInverseYawOnly
        );
        assert_eq!(
            CameraLatencyReprojectionMode::from_code(6),
            CameraLatencyReprojectionMode::RotationOnlySensorTimestampCameraCalibrated
        );
        assert_eq!(
            CameraLatencyReprojectionMode::from_code(99),
            CameraLatencyReprojectionMode::Off
        );
        assert!(CameraLatencyReprojectionMode::RotationOnlySensorTimestamp.uses_sensor_timestamp());
        assert!(
            CameraLatencyReprojectionMode::RotationOnlySensorTimestampInverse.inverse_direction()
        );
        assert!(
            CameraLatencyReprojectionMode::RotationOnlySensorTimestampInverseRollFree
                .uses_sensor_timestamp()
        );
        assert_eq!(
            CameraLatencyReprojectionMode::RotationOnlySensorTimestampInverseRollFree.axis_filter(),
            CameraLatencyRotationAxisFilter::RollFree
        );
        assert_eq!(
            CameraLatencyReprojectionMode::RotationOnlySensorTimestampInverseYawOnly.axis_filter(),
            CameraLatencyRotationAxisFilter::YawOnly
        );
        assert!(
            CameraLatencyReprojectionMode::RotationOnlySensorTimestampCameraCalibrated
                .uses_camera_calibration()
        );
    }

    #[test]
    fn camera_extrinsic_conjugation_flips_yaw_but_preserves_pitch() {
        let sensor_to_camera =
            quaternion_rotation_matrix([1.0, 0.0, 0.0, 0.0]).expect("180-degree X rotation");
        let angle = 10.0_f32.to_radians();
        let (sin, cos) = angle.sin_cos();
        let yaw = [[cos, 0.0, sin], [0.0, 1.0, 0.0], [-sin, 0.0, cos]];
        let calibrated_yaw = multiply3(
            multiply3(sensor_to_camera, yaw),
            transpose3(sensor_to_camera),
        );
        assert!((calibrated_yaw[0][2] + sin).abs() < 0.0001);
        assert!((calibrated_yaw[2][0] - sin).abs() < 0.0001);

        let pitch = [[1.0, 0.0, 0.0], [0.0, cos, -sin], [0.0, sin, cos]];
        let calibrated_pitch = multiply3(
            multiply3(sensor_to_camera, pitch),
            transpose3(sensor_to_camera),
        );
        for row in 0..3 {
            for column in 0..3 {
                assert!((calibrated_pitch[row][column] - pitch[row][column]).abs() < 0.0001);
            }
        }
    }

    #[test]
    fn camera_reprojection_axis_filters_remove_roll_and_optional_pitch() {
        let rolled = CameraLatencyViewerBasis {
            timestamp_ns: 10,
            right: [0.0, 1.0, 0.0],
            up: [-1.0, 0.0, 0.0],
            forward: [0.0, 0.0, -1.0],
        };
        let roll_free = filter_viewer_basis(rolled, CameraLatencyRotationAxisFilter::RollFree)
            .expect("horizontal forward has a roll-free basis");
        assert!((roll_free.right[0] - 1.0).abs() < 0.0001);
        assert!((roll_free.up[1] - 1.0).abs() < 0.0001);
        assert!((roll_free.forward[2] + 1.0).abs() < 0.0001);

        let pitched = CameraLatencyViewerBasis {
            timestamp_ns: 11,
            right: [1.0, 0.0, 0.0],
            up: [0.0, 0.866_025_4, 0.5],
            forward: [0.0, 0.5, -0.866_025_4],
        };
        let yaw_only = filter_viewer_basis(pitched, CameraLatencyRotationAxisFilter::YawOnly)
            .expect("horizontal projection has a yaw-only basis");
        assert!(yaw_only.forward[1].abs() < 0.0001);
        assert!((yaw_only.forward[2] + 1.0).abs() < 0.0001);
        let roll_free = filter_viewer_basis(pitched, CameraLatencyRotationAxisFilter::RollFree)
            .expect("pitched forward has a roll-free basis");
        assert!(roll_free.forward[1] > 0.49);
    }

    #[test]
    fn camera_latency_window_classifies_atomic_and_single_eye_imports() {
        let mut window = CameraLatencyWindow::new(10, 20, 100, 200);
        let mut record = |left_imported, right_imported| {
            window.record(
                CameraLatencyFrameTiming::default(),
                left_imported,
                right_imported,
                10,
                20,
                100,
                200,
                None,
                None,
                None,
                None,
                true,
            );
        };
        record(true, true);
        record(true, false);
        record(false, true);
        record(false, false);
        drop(record);

        assert_eq!(window.both_eye_import_presents, 1);
        assert_eq!(window.left_only_import_presents, 1);
        assert_eq!(window.right_only_import_presents, 1);
        assert_eq!(window.held_pair_presents, 1);
    }
}
