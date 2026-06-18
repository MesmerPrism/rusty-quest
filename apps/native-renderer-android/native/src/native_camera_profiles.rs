//! Public Camera2 request profile helpers for the native HWB route.

use crate::{
    acamera_sys::{ACameraDevice_request_template, TEMPLATE_PREVIEW, TEMPLATE_RECORD},
    native_renderer_options::NativeCameraQualityProfile,
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum CameraRequestTemplate {
    Preview,
    Record,
}

impl CameraRequestTemplate {
    pub(crate) fn ndk_value(self) -> ACameraDevice_request_template {
        match self {
            Self::Preview => TEMPLATE_PREVIEW,
            Self::Record => TEMPLATE_RECORD,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Preview => "preview",
            Self::Record => "record",
        }
    }
}

impl NativeCameraQualityProfile {
    pub(crate) fn request_template(self) -> CameraRequestTemplate {
        match self {
            Self::DirectLowNoiseRecord30 => CameraRequestTemplate::Record,
            Self::DirectBaseline
            | Self::DirectLowNoise30
            | Self::DirectLowLatency60
            | Self::DirectQualityProbe => CameraRequestTemplate::Preview,
        }
    }

    pub(crate) fn target_ae_fps_range(self) -> Option<[i32; 2]> {
        match self {
            Self::DirectLowNoise30 | Self::DirectLowNoiseRecord30 | Self::DirectQualityProbe => {
                Some([30, 30])
            }
            Self::DirectLowLatency60 => Some([60, 60]),
            Self::DirectBaseline => None,
        }
    }
}
