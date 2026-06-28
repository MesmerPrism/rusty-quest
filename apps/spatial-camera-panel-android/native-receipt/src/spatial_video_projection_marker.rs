#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use crate::android_log_info;

pub(crate) const SPATIAL_VIDEO_PROJECTION_CHANNEL: &str = "spatial-video-projection";

pub(crate) fn log_spatial_video_projection_marker(fields: String) {
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel={} {}",
            SPATIAL_VIDEO_PROJECTION_CHANNEL, fields
        ),
    );
}
