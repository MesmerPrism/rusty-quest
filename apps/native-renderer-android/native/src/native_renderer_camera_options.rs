//! Camera, guide, and swapchain option types for the native renderer.

use crate::native_renderer_property_values::normalized_property;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeCameraOutputMode {
    Auto,
    DirectHwb,
    GuidePublic,
    Disabled,
}

impl NativeCameraOutputMode {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "direct" | "direct-hwb" | "direct-hardware-buffer" | "raw" | "raw-hwb" => {
                Self::DirectHwb
            }
            "guide" | "guide-public" | "public-guide" | "guide-texture" => Self::GuidePublic,
            "0" | "false" | "no" | "off" | "disabled" => Self::Disabled,
            _ => Self::Auto,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::DirectHwb => "direct-hwb",
            Self::GuidePublic => "guide-public",
            Self::Disabled => "disabled",
        }
    }

    pub(crate) fn camera_import_enabled(self) -> bool {
        !matches!(self, Self::Disabled)
    }

    pub(crate) fn private_layer_projection_enabled(self) -> bool {
        matches!(self, Self::Auto)
    }

    pub(crate) fn guide_projection_enabled(self) -> bool {
        matches!(self, Self::Auto | Self::GuidePublic)
    }

    pub(crate) fn guide_graph_processing_enabled(self) -> bool {
        matches!(self, Self::Auto | Self::GuidePublic)
    }

    pub(crate) fn direct_hwb_forced(self) -> bool {
        matches!(self, Self::DirectHwb)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeGuideGraphResolution {
    Low384,
    Camera1280,
}

impl Default for NativeGuideGraphResolution {
    fn default() -> Self {
        Self::Low384
    }
}

impl NativeGuideGraphResolution {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "camera" | "camera-native" | "camera-sized" | "native-camera" | "1280"
            | "1280x1280" => Self::Camera1280,
            _ => Self::Low384,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Low384 => "low-384",
            Self::Camera1280 => "camera-1280",
        }
    }

    pub(crate) fn extent(self) -> [u32; 2] {
        match self {
            Self::Low384 => [384, 384],
            Self::Camera1280 => [1280, 1280],
        }
    }

    pub(crate) fn path_prefix(self) -> &'static str {
        match self {
            Self::Low384 => "low-resolution",
            Self::Camera1280 => "camera-resolution",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeCameraYcbcrMode {
    AndroidSuggested,
    ForcedBt601Narrow,
}

impl NativeCameraYcbcrMode {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "bt601-narrow"
            | "bt601-limited"
            | "forced-bt601"
            | "forced-bt601-narrow"
            | "forced-bt601-limited"
            | "cpuyuv-reference" => Self::ForcedBt601Narrow,
            _ => Self::AndroidSuggested,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::AndroidSuggested => "android-suggested",
            Self::ForcedBt601Narrow => "forced-bt601-narrow",
        }
    }

    pub(crate) fn conversion_mode(self) -> &'static str {
        match self {
            Self::AndroidSuggested => "android-suggested-ycbcr",
            Self::ForcedBt601Narrow => "forced-bt601-limited-cpuyuv-reference",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeCameraResolutionProfile {
    Square1280,
    Wide1280x960,
    ClosestSupported,
}

impl NativeCameraResolutionProfile {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "1280x960" | "wide-1280x960" | "quest-1280x960" => Self::Wide1280x960,
            "closest" | "closest-supported" | "auto-supported" => Self::ClosestSupported,
            _ => Self::Square1280,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Square1280 => "1280x1280",
            Self::Wide1280x960 => "1280x960",
            Self::ClosestSupported => "closest-supported",
        }
    }

    pub(crate) fn requested_size(self) -> Option<[i32; 2]> {
        match self {
            Self::Square1280 => Some([1280, 1280]),
            Self::Wide1280x960 => Some([1280, 960]),
            Self::ClosestSupported => None,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeCameraQualityProfile {
    DirectBaseline,
    DirectLowNoise30,
    DirectLowNoiseRecord30,
    DirectLowLatency60,
    DirectQualityProbe,
}

impl NativeCameraQualityProfile {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "direct-low-noise-30" | "low-noise-30" | "noise-30" | "low-noise" => {
                Self::DirectLowNoise30
            }
            "direct-low-noise-record-30"
            | "low-noise-record-30"
            | "record-low-noise-30"
            | "record-30" => Self::DirectLowNoiseRecord30,
            "direct-low-latency-60" | "low-latency-60" | "latency-60" | "low-latency" => {
                Self::DirectLowLatency60
            }
            "direct-quality-probe" | "quality-probe" | "quality" => Self::DirectQualityProbe,
            _ => Self::DirectBaseline,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::DirectBaseline => "direct-baseline",
            Self::DirectLowNoise30 => "direct-low-noise-30",
            Self::DirectLowNoiseRecord30 => "direct-low-noise-record-30",
            Self::DirectLowLatency60 => "direct-low-latency-60",
            Self::DirectQualityProbe => "direct-quality-probe",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeCameraSyncMode {
    EarlyDeleteAhbRetained,
    HoldImageUntilGpuFence,
    DeleteAsyncReleaseFence,
}

impl NativeCameraSyncMode {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "hold-image-until-gpu-fence" | "hold-image" | "hold-image-until-fence" => {
                Self::HoldImageUntilGpuFence
            }
            "delete-async-release-fence" | "delete-async" | "async-release-fence" => {
                Self::DeleteAsyncReleaseFence
            }
            _ => Self::EarlyDeleteAhbRetained,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::EarlyDeleteAhbRetained => "early-delete-ahb-retained",
            Self::HoldImageUntilGpuFence => "hold-image-until-gpu-fence",
            Self::DeleteAsyncReleaseFence => "delete-async-release-fence",
        }
    }

    pub(crate) fn active_marker_value(self) -> &'static str {
        match self {
            Self::EarlyDeleteAhbRetained => "early-delete-ahb-retained",
            Self::HoldImageUntilGpuFence => "hold-image-until-gpu-fence",
            Self::DeleteAsyncReleaseFence => "delete-async-release-fence",
        }
    }

    pub(crate) fn implementation_status(self) -> &'static str {
        match self {
            Self::EarlyDeleteAhbRetained => "active-baseline",
            Self::HoldImageUntilGpuFence => "active-diagnostic",
            Self::DeleteAsyncReleaseFence => {
                "active-diagnostic-sync-fd-observed-vulkan-semaphore-pending"
            }
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeCameraStereoPairingPolicy {
    LatestLatest,
    NearestTimestamp,
}

impl NativeCameraStereoPairingPolicy {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "nearest-timestamp" | "nearest" | "timestamp-nearest" => Self::NearestTimestamp,
            _ => Self::LatestLatest,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::LatestLatest => "latest-latest",
            Self::NearestTimestamp => "nearest-timestamp",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeSwapchainColorFormatMode {
    Auto,
    Srgb,
    Unorm,
}

impl NativeSwapchainColorFormatMode {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "srgb" | "s-rgb" | "prefer-srgb" => Self::Srgb,
            "unorm" | "linear" | "prefer-unorm" => Self::Unorm,
            _ => Self::Auto,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Auto => "auto-srgb-preferred",
            Self::Srgb => "srgb",
            Self::Unorm => "unorm",
        }
    }
}
