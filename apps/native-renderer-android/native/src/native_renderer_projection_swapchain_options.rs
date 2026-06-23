//! OpenXR projection swapchain startup settings for the native renderer.

use crate::{
    native_renderer_properties::PROP_PROJECTION_SWAPCHAIN_RESOLUTION_SCALE,
    native_renderer_property_values::f32_clamped_value,
};

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct NativeProjectionSwapchainSettings {
    pub(crate) resolution_scale: f32,
}

impl NativeProjectionSwapchainSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        Self {
            resolution_scale: f32_clamped_value(
                lookup(PROP_PROJECTION_SWAPCHAIN_RESOLUTION_SCALE),
                1.0,
                0.50,
                1.0,
            ),
        }
    }

    pub(crate) fn scaled_dimension(self, recommended: u32) -> u32 {
        if self.resolution_scale >= 0.999 {
            return recommended;
        }
        let scaled = ((recommended as f32) * self.resolution_scale).round() as u32;
        scaled.max(1)
    }

    pub(crate) fn scale_applied(self) -> bool {
        self.resolution_scale < 0.999
    }
}
