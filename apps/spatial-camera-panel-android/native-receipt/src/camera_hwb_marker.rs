#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use crate::android_log_info;

pub(crate) const CAMERA_HWB_PROBE_CHANNEL: &str = "camera-hwb-spatial-probe";

pub(crate) fn log_camera_hwb_marker(fields: String) {
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel={} {}",
            CAMERA_HWB_PROBE_CHANNEL, fields
        ),
    );
}
