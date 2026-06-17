//! Live OpenXR hand tracking adapter for the compact GPU skinning input path.
//!
//! This keeps live Meta hand input shaped like the recorded replay: 21 runtime
//! joint poses plus 5 derived tip lengths. Static topology, bind poses, joint
//! weights, triangles, and derived skinned/SDF buffers remain Vulkan-resident.

use std::mem;

use openxr as xr;

use crate::recorded_hand_replay::{RecordedHandGpuPose, RecordedHandSkinningFrame};

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

const TIP_PAIRS: [(xr::HandJoint, xr::HandJoint); 5] = [
    (xr::HandJoint::THUMB_DISTAL, xr::HandJoint::THUMB_TIP),
    (xr::HandJoint::INDEX_DISTAL, xr::HandJoint::INDEX_TIP),
    (xr::HandJoint::MIDDLE_DISTAL, xr::HandJoint::MIDDLE_TIP),
    (xr::HandJoint::RING_DISTAL, xr::HandJoint::RING_TIP),
    (xr::HandJoint::LITTLE_DISTAL, xr::HandJoint::LITTLE_TIP),
];

#[derive(Clone, Debug)]
pub(crate) struct LiveHandCompactStats {
    pub(crate) extension_available: bool,
    pub(crate) extension_enabled: bool,
    pub(crate) system_supported: bool,
    pub(crate) tracker_ready: bool,
    pub(crate) frame_ready: bool,
    pub(crate) left_active: bool,
    pub(crate) right_active: bool,
    pub(crate) using_left: bool,
    pub(crate) using_right: bool,
    pub(crate) active_hand_count: u32,
    pub(crate) visualizable_hand_count: u32,
    pub(crate) frame_index: u32,
    pub(crate) timestamp_ns: u64,
    pub(crate) runtime_joint_pose_count: usize,
    pub(crate) tip_length_count: usize,
    pub(crate) compact_upload_bytes: u64,
    pub(crate) reason: &'static str,
}

impl LiveHandCompactStats {
    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "liveMetaHandCompactInputReady={} liveMetaHandCompactFrameReady={} liveMetaHandTrackingExtensionAvailable={} liveMetaHandTrackingExtensionEnabled={} liveMetaHandTrackingSystemSupported={} liveMetaHandTrackerReady={} liveMetaHandFrameSource=XR_EXT_hand_tracking liveMetaHandCompactUploadEquivalent=true liveMetaHandGpuInputPath=recorded-compatible-compact-joint-pose-tip-length liveMetaHandRuntimeJointPoseCount={} liveMetaHandTipLengthCount={} liveMetaHandCompactFrameUploadBytes={} liveMetaHandLeftActive={} liveMetaHandRightActive={} liveMetaHandUsingLeft={} liveMetaHandUsingRight={} liveMetaHandUsingBoth={} liveMetaHandActiveHandCount={} liveMetaHandVisualizableHandCount={} liveMetaHandFrameIndex={} liveMetaHandTimestampNs={} liveMetaHandFallbackReason={}",
            self.tracker_ready,
            self.frame_ready,
            self.extension_available,
            self.extension_enabled,
            self.system_supported,
            self.tracker_ready,
            self.runtime_joint_pose_count,
            self.tip_length_count,
            self.compact_upload_bytes,
            self.left_active,
            self.right_active,
            self.using_left,
            self.using_right,
            self.using_left && self.using_right,
            self.active_hand_count,
            self.visualizable_hand_count,
            self.frame_index,
            self.timestamp_ns,
            self.reason,
        )
    }
}

impl Default for LiveHandCompactStats {
    fn default() -> Self {
        Self {
            extension_available: false,
            extension_enabled: false,
            system_supported: false,
            tracker_ready: false,
            frame_ready: false,
            left_active: false,
            right_active: false,
            using_left: false,
            using_right: false,
            active_hand_count: 0,
            visualizable_hand_count: 0,
            frame_index: 0,
            timestamp_ns: 0,
            runtime_joint_pose_count: 0,
            tip_length_count: 0,
            compact_upload_bytes: 0,
            reason: "unavailable",
        }
    }
}

#[derive(Clone, Debug, Default)]
pub(crate) struct LiveHandCompactFrameSet {
    pub(crate) left: Option<RecordedHandSkinningFrame>,
    pub(crate) right: Option<RecordedHandSkinningFrame>,
}

impl LiveHandCompactFrameSet {
    pub(crate) fn primary_frame(&self) -> Option<&RecordedHandSkinningFrame> {
        self.left.as_ref().or(self.right.as_ref())
    }

    pub(crate) fn primary_handedness(&self) -> Option<&'static str> {
        if self.left.is_some() {
            Some("left")
        } else if self.right.is_some() {
            Some("right")
        } else {
            None
        }
    }

    pub(crate) fn secondary_frame(&self) -> Option<&RecordedHandSkinningFrame> {
        if self.left.is_some() {
            self.right.as_ref()
        } else {
            None
        }
    }

    pub(crate) fn secondary_handedness(&self) -> Option<&'static str> {
        (self.left.is_some() && self.right.is_some()).then_some("right")
    }
}

pub(crate) struct LiveHandCompactInput {
    extension_available: bool,
    extension_enabled: bool,
    system_supported: bool,
    left_tracker: Option<xr::HandTracker>,
    right_tracker: Option<xr::HandTracker>,
    frame_counter: u32,
    create_error: Option<String>,
}

impl LiveHandCompactInput {
    pub(crate) fn new(
        instance: &xr::Instance,
        system: xr::SystemId,
        session: &xr::Session<xr::Vulkan>,
        extension_available: bool,
        extension_enabled: bool,
    ) -> Self {
        let mut input = Self {
            extension_available,
            extension_enabled,
            system_supported: false,
            left_tracker: None,
            right_tracker: None,
            frame_counter: 0,
            create_error: None,
        };

        if !extension_enabled {
            crate::marker(
                "live-hand-compact",
                format!(
                    "status=disabled {}",
                    input.stats("extension-not-enabled").marker_fields()
                ),
            );
            return input;
        }

        match instance.supports_hand_tracking(system) {
            Ok(supported) => input.system_supported = supported,
            Err(error) => {
                input.create_error = Some(format!("supports_hand_tracking failed: {error}"));
                crate::marker(
                    "live-hand-compact",
                    format!(
                        "status=error reason={} {}",
                        crate::sanitize(input.create_error.as_deref().unwrap_or("unknown")),
                        input.stats("system-support-query-failed").marker_fields()
                    ),
                );
                return input;
            }
        }
        if !input.system_supported {
            crate::marker(
                "live-hand-compact",
                format!(
                    "status=unsupported {}",
                    input
                        .stats("system-does-not-support-hand-tracking")
                        .marker_fields()
                ),
            );
            return input;
        }

        input.left_tracker = match session.create_hand_tracker(xr::Hand::LEFT) {
            Ok(tracker) => Some(tracker),
            Err(error) => {
                input.create_error = Some(format!("left create_hand_tracker failed: {error}"));
                None
            }
        };
        input.right_tracker = match session.create_hand_tracker(xr::Hand::RIGHT) {
            Ok(tracker) => Some(tracker),
            Err(error) => {
                let existing = input.create_error.take();
                input.create_error = Some(match existing {
                    Some(existing) => {
                        format!("{existing}; right create_hand_tracker failed: {error}")
                    }
                    None => format!("right create_hand_tracker failed: {error}"),
                });
                None
            }
        };

        let status = if input.tracker_ready() {
            "created"
        } else {
            "error"
        };
        crate::marker(
            "live-hand-compact",
            format!(
                "status={} reason={} {}",
                status,
                crate::sanitize(input.create_error.as_deref().unwrap_or("ready")),
                input.stats("no-active-frame-yet").marker_fields()
            ),
        );
        input
    }

    pub(crate) fn locate_compact_frame(
        &mut self,
        reference_space: &xr::Space,
        predicted_display_time: xr::Time,
        expected_runtime_joint_count: usize,
        expected_tip_length_count: usize,
    ) -> (LiveHandCompactFrameSet, LiveHandCompactStats) {
        let mut stats = self.stats("no-active-frame");
        if !self.tracker_ready() {
            stats.reason = "tracker-not-ready";
            return (LiveHandCompactFrameSet::default(), stats);
        }
        if expected_runtime_joint_count != RUNTIME_JOINTS.len()
            || expected_tip_length_count != TIP_PAIRS.len()
        {
            stats.reason = "recorded-compact-shape-mismatch";
            return (LiveHandCompactFrameSet::default(), stats);
        }

        let left = self
            .left_tracker
            .as_ref()
            .and_then(|tracker| locate_hand(reference_space, tracker, predicted_display_time).ok())
            .flatten();
        let right = self
            .right_tracker
            .as_ref()
            .and_then(|tracker| locate_hand(reference_space, tracker, predicted_display_time).ok())
            .flatten();
        stats.left_active = left.is_some();
        stats.right_active = right.is_some();
        stats.active_hand_count = stats.left_active as u32 + stats.right_active as u32;

        if left.is_none() && right.is_none() {
            stats.reason = "no-active-hand";
            return (LiveHandCompactFrameSet::default(), stats);
        }

        self.frame_counter = self.frame_counter.wrapping_add(1);
        let frame_index = self.frame_counter;
        let timestamp_ns = predicted_display_time.as_nanos().max(0) as u64;

        let left_frame = left.as_ref().and_then(|locations| {
            compact_frame_from_locations(locations, frame_index, timestamp_ns).ok()
        });
        let right_frame = right.as_ref().and_then(|locations| {
            compact_frame_from_locations(locations, frame_index, timestamp_ns).ok()
        });

        stats.using_left = left_frame.is_some();
        stats.using_right = right_frame.is_some();
        stats.visualizable_hand_count = stats.using_left as u32 + stats.using_right as u32;
        if stats.visualizable_hand_count == 0 {
            stats.reason = "invalid-live-hand-frame";
            stats.frame_index = frame_index;
            stats.timestamp_ns = timestamp_ns;
            return (LiveHandCompactFrameSet::default(), stats);
        }

        stats.frame_ready = true;
        stats.frame_index = frame_index;
        stats.timestamp_ns = timestamp_ns;
        stats.runtime_joint_pose_count = expected_runtime_joint_count;
        stats.tip_length_count = expected_tip_length_count;
        stats.compact_upload_bytes = left_frame
            .iter()
            .chain(right_frame.iter())
            .map(|frame| compact_upload_bytes(&frame.runtime_joint_poses, &frame.tip_length_rows))
            .sum();
        stats.reason = if stats.using_left && stats.using_right {
            "live-both-frames-ready"
        } else if stats.using_left {
            "live-left-frame-ready"
        } else {
            "live-right-frame-ready"
        };

        (
            LiveHandCompactFrameSet {
                left: left_frame,
                right: right_frame,
            },
            stats,
        )
    }

    fn tracker_ready(&self) -> bool {
        self.left_tracker.is_some() || self.right_tracker.is_some()
    }

    fn stats(&self, reason: &'static str) -> LiveHandCompactStats {
        LiveHandCompactStats {
            extension_available: self.extension_available,
            extension_enabled: self.extension_enabled,
            system_supported: self.system_supported,
            tracker_ready: self.tracker_ready(),
            reason,
            ..Default::default()
        }
    }
}

fn compact_frame_from_locations(
    locations: &xr::HandJointLocations,
    frame_index: u32,
    timestamp_ns: u64,
) -> Result<RecordedHandSkinningFrame, &'static str> {
    let runtime_joint_poses = runtime_joint_poses(locations)?;
    let tip_lengths = tip_lengths(locations)?;
    Ok(RecordedHandSkinningFrame {
        frame_index,
        timestamp_ns,
        runtime_joint_poses,
        tip_length_rows: pack_tip_length_rows(&tip_lengths),
    })
}

fn locate_hand(
    reference_space: &xr::Space,
    tracker: &xr::HandTracker,
    time: xr::Time,
) -> xr::Result<Option<xr::HandJointLocations>> {
    reference_space.locate_hand_joints(tracker, time)
}

fn runtime_joint_poses(
    locations: &xr::HandJointLocations,
) -> Result<Vec<RecordedHandGpuPose>, &'static str> {
    RUNTIME_JOINTS
        .iter()
        .copied()
        .map(|joint| gpu_pose_for_joint(locations, joint))
        .collect()
}

fn tip_lengths(locations: &xr::HandJointLocations) -> Result<Vec<f32>, &'static str> {
    TIP_PAIRS
        .iter()
        .copied()
        .map(|(distal, tip)| {
            let distal = valid_location(locations, distal)?;
            let tip = valid_location(locations, tip)?;
            Ok(distance(distal.pose.position, tip.pose.position))
        })
        .collect()
}

fn gpu_pose_for_joint(
    locations: &xr::HandJointLocations,
    joint: xr::HandJoint,
) -> Result<RecordedHandGpuPose, &'static str> {
    let location = valid_location(locations, joint)?;
    Ok(RecordedHandGpuPose {
        translation_pad: [
            location.pose.position.x,
            location.pose.position.y,
            location.pose.position.z,
            location.radius,
        ],
        rotation_xyzw: [
            location.pose.orientation.x,
            location.pose.orientation.y,
            location.pose.orientation.z,
            location.pose.orientation.w,
        ],
    })
}

fn valid_location(
    locations: &xr::HandJointLocations,
    joint: xr::HandJoint,
) -> Result<&xr::HandJointLocation, &'static str> {
    let location = &locations[joint];
    if location.location_flags.contains(
        xr::SpaceLocationFlags::POSITION_VALID | xr::SpaceLocationFlags::ORIENTATION_VALID,
    ) {
        Ok(location)
    } else {
        Err("joint-location-invalid")
    }
}

fn distance(left: xr::Vector3f, right: xr::Vector3f) -> f32 {
    let dx = left.x - right.x;
    let dy = left.y - right.y;
    let dz = left.z - right.z;
    (dx * dx + dy * dy + dz * dz).sqrt()
}

fn pack_tip_length_rows(tip_lengths: &[f32]) -> Vec<[f32; 4]> {
    tip_lengths
        .chunks(4)
        .map(|chunk| {
            let mut row = [0.0; 4];
            row[..chunk.len()].copy_from_slice(chunk);
            row
        })
        .collect()
}

fn compact_upload_bytes(
    runtime_joint_poses: &[RecordedHandGpuPose],
    tip_length_rows: &[[f32; 4]],
) -> u64 {
    mem::size_of_val(runtime_joint_poses) as u64 + mem::size_of_val(tip_length_rows) as u64
}
