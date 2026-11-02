//! Capability-free adapter for shared display render code. No file/network effects.
#![allow(unused_variables)]
use crate::{
    display_composite_native_stream::DisplayCompositeFrame,
    native_renderer_display_composite_options::NativeDisplayCompositeSettings,
};
use ash::vk;
pub(crate) fn configure(
    app: &android_activity::AndroidApp,
    settings: NativeDisplayCompositeSettings,
) {
}
pub(crate) fn export_enabled() -> bool {
    false
}
pub(crate) fn source_export_done() -> bool {
    false
}
pub(crate) fn gpu_export_done() -> bool {
    false
}
pub(crate) fn feedback_level_export_done() -> bool {
    false
}
pub(crate) fn reserve_feedback_level_export_index() -> Option<u32> {
    None
}
pub(crate) fn stable_export_frame_ready(
    frame: &DisplayCompositeFrame,
    render_frame_count: u64,
    xr_ready_marker_active: bool,
) -> bool {
    false
}
pub(crate) fn xr_gpu_upload_ready(render_frame_count: u64, xr_ready_marker_active: bool) -> bool {
    false
}
pub(crate) fn media_projection_source_ready(frame: &DisplayCompositeFrame) -> bool {
    false
}
pub(crate) fn xr_ready_marker_label() -> &'static str {
    "unavailable"
}
pub(crate) fn try_export_media_projection_frame(
    frame: &DisplayCompositeFrame,
    render_frame_count: u64,
    xr_ready_marker_active: bool,
) {
}
pub(crate) fn write_gpu_sampled_frame(
    frame_index: u64,
    import_sequence: u64,
    timestamp_ns: i64,
    hardware_buffer_id: u64,
    width: u32,
    height: u32,
    format: vk::Format,
    render_frame_count: u64,
    xr_ready_marker_active: bool,
    bytes: &[u8],
) {
}
pub(crate) fn write_gpu_source_level_frame(
    level_index: u32,
    frame_index: u64,
    import_sequence: u64,
    timestamp_ns: i64,
    hardware_buffer_id: u64,
    width: u32,
    height: u32,
    format: vk::Format,
    render_frame_count: u64,
    xr_ready_marker_active: bool,
    recursive_feedback_seeded: bool,
    bytes: &[u8],
) {
}
pub(crate) fn write_feedback_screen_level_frame(
    level_index: u32,
    frame_index: u64,
    import_sequence: u64,
    timestamp_ns: i64,
    hardware_buffer_id: u64,
    width: u32,
    height: u32,
    format: vk::Format,
    render_frame_count: u64,
    xr_ready_marker_active: bool,
    recursive_feedback_seeded: bool,
    recursive_feedback_write_index: u32,
    recursive_feedback_read_index: i32,
    bytes: &[u8],
) {
}
