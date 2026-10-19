//! OpenXR projection swapchain startup settings for the native renderer.

use crate::{
    native_renderer_properties::PROP_PROJECTION_SWAPCHAIN_RESOLUTION_SCALE,
    native_renderer_property_values::f32_value,
};

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct NativeProjectionSwapchainSettings {
    pub(crate) resolution_scale: f32,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct NativeProjectionSwapchainExtent {
    pub(crate) width: u32,
    pub(crate) height: u32,
    /// Common scale before integer dimension rounding.
    pub(crate) effective_scale: f32,
    pub(crate) limits_clamped: bool,
}

fn bounded_scale(value: f32) -> f32 {
    if value.is_finite() {
        value.clamp(0.50, 1.40)
    } else {
        1.0
    }
}

impl NativeProjectionSwapchainSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        Self {
            resolution_scale: bounded_scale(f32_value(
                lookup(PROP_PROJECTION_SWAPCHAIN_RESOLUTION_SCALE),
                1.0,
            )),
        }
    }

    pub(crate) fn scaled_dimension(self, recommended: u32) -> u32 {
        let scale = bounded_scale(self.resolution_scale);
        if scale == 1.0 {
            return recommended;
        }
        ((recommended as f64 * scale as f64).round()).clamp(1.0, u32::MAX as f64) as u32
    }

    pub(crate) fn scale_applied(self) -> bool {
        bounded_scale(self.resolution_scale) != 1.0
    }

    /// Caller combines both OpenXR view maxima, system maxima and Vulkan limits.
    /// Use one scale for both axes; integer rounding never exceeds the supplied maxima.
    pub(crate) fn resolve_extent(
        self,
        recommended: [u32; 2],
        maximum: [u32; 2],
    ) -> Result<NativeProjectionSwapchainExtent, String> {
        if recommended.contains(&0) || maximum.contains(&0) {
            return Err("projection swapchain dimensions and limits must be nonzero".into());
        }
        if recommended.iter().zip(maximum).any(|(r, m)| *r > m) {
            return Err(
                "projection recommended extent exceeds combined runtime/device limits".into(),
            );
        }
        let requested = bounded_scale(self.resolution_scale) as f64;
        let effective = requested
            .min(maximum[0] as f64 / recommended[0] as f64)
            .min(maximum[1] as f64 / recommended[1] as f64);
        let dimension = |axis: usize| {
            (recommended[axis] as f64 * effective)
                .round()
                .clamp(1.0, maximum[axis] as f64) as u32
        };
        Ok(NativeProjectionSwapchainExtent {
            width: dimension(0),
            height: dimension(1),
            effective_scale: effective as f32,
            limits_clamped: effective < requested,
        })
    }
}
