//! OpenXR foveation startup settings for the native renderer.
//!
//! These settings are swapchain-scoped: Android properties must be applied
//! before launching the native activity so the OpenXR instance and projection
//! swapchain can request and bind the relevant Meta extensions.

use crate::{
    native_renderer_properties::{
        PROP_FOVEATION_DYNAMIC, PROP_FOVEATION_LEVEL, PROP_FOVEATION_MODE,
        PROP_FOVEATION_VERTICAL_OFFSET, PROP_FOVEATION_VULKAN_FDM,
    },
    native_renderer_property_values::{bool_value, f32_clamped_value, normalized_property},
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeFoveationMode {
    Disabled,
    Fixed,
}

impl NativeFoveationMode {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "1" | "true" | "yes" | "on" | "enabled" | "fixed" | "fixed-level" => Self::Fixed,
            _ => Self::Disabled,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::Fixed => "fixed",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeFoveationLevel {
    Low,
    Medium,
    High,
}

impl NativeFoveationLevel {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "0" | "low" => Self::Low,
            "2" | "high" | "high-top" => Self::High,
            _ => Self::Medium,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Low => "low",
            Self::Medium => "medium",
            Self::High => "high",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct NativeFoveationSettings {
    pub(crate) mode: NativeFoveationMode,
    pub(crate) level: NativeFoveationLevel,
    pub(crate) dynamic: bool,
    pub(crate) vertical_offset: f32,
    pub(crate) vulkan_fdm: bool,
}

impl NativeFoveationSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        Self {
            mode: NativeFoveationMode::from_property(lookup(PROP_FOVEATION_MODE)),
            level: NativeFoveationLevel::from_property(lookup(PROP_FOVEATION_LEVEL)),
            dynamic: bool_value(lookup(PROP_FOVEATION_DYNAMIC), false),
            vertical_offset: f32_clamped_value(
                lookup(PROP_FOVEATION_VERTICAL_OFFSET),
                0.0,
                -1.0,
                1.0,
            ),
            vulkan_fdm: bool_value(lookup(PROP_FOVEATION_VULKAN_FDM), false),
        }
    }

    pub(crate) fn requested(self) -> bool {
        matches!(self.mode, NativeFoveationMode::Fixed)
    }

    pub(crate) fn vulkan_fdm_requested(self) -> bool {
        self.requested() && self.vulkan_fdm
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "foveationRequested={} foveationMode={} foveationLevel={} foveationDynamic={} foveationVerticalOffset={:.3} foveationVulkanFdmRequested={} foveationScope=openxr-projection-swapchain-startup",
            self.requested(),
            self.mode.marker_value(),
            self.level.marker_value(),
            self.dynamic,
            self.vertical_offset,
            self.vulkan_fdm_requested()
        )
    }
}
