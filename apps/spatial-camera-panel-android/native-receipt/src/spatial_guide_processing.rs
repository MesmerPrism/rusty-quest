//! Generic, low-rate guide-processing and camera-ingress sampling policy for the
//! Spatial camera stack.
//!
//! The public carrier owns only selectable kernel, input-treatment, and generic
//! anti-aliasing shapes. Downstream opaque shaders continue to own the meaning of
//! their guide data.

use std::sync::atomic::{AtomicU32, Ordering};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub(crate) enum SpatialCameraSampling {
    Linear = 0,
    ThinLineTent5 = 1,
}

impl SpatialCameraSampling {
    pub(crate) fn from_code(code: u32) -> Self {
        match code {
            1 => Self::ThinLineTent5,
            _ => Self::Linear,
        }
    }

    pub(crate) fn parse(token: &str) -> Option<Self> {
        match token.trim().to_ascii_lowercase().replace('_', "-").as_str() {
            "linear" | "bilinear" | "0" => Some(Self::Linear),
            "thin-line-aa" | "thin-line-tent5" | "tent5" | "aa" | "1" => Some(Self::ThinLineTent5),
            _ => None,
        }
    }

    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::Linear => "linear",
            Self::ThinLineTent5 => "thin-line-tent5",
        }
    }

    pub(crate) fn raw_projection_push_code(self) -> f32 {
        self as u32 as f32
    }

    pub(crate) fn opaque_guide_push_flags(self) -> f32 {
        match self {
            Self::Linear => 0.0,
            Self::ThinLineTent5 => 2.0,
        }
    }

    pub(crate) fn radius_texels(self) -> f32 {
        match self {
            Self::Linear => 0.0,
            Self::ThinLineTent5 => 0.75,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub(crate) enum SpatialGuideBlurKernel {
    NativeBox5 = 0,
    Gaussian5 = 1,
}

impl SpatialGuideBlurKernel {
    pub(crate) fn from_code(code: u32) -> Self {
        match code {
            1 => Self::Gaussian5,
            _ => Self::NativeBox5,
        }
    }

    pub(crate) fn parse(token: &str) -> Option<Self> {
        match token.trim().to_ascii_lowercase().replace('_', "-").as_str() {
            "native-box5" | "box5" | "box" | "native" | "0" => Some(Self::NativeBox5),
            "gaussian5" | "gaussian" | "1" => Some(Self::Gaussian5),
            _ => None,
        }
    }

    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::NativeBox5 => "native-box5",
            Self::Gaussian5 => "gaussian5",
        }
    }

    #[cfg(test)]
    pub(crate) fn weights(self) -> [f32; 5] {
        match self {
            Self::NativeBox5 => [0.2; 5],
            Self::Gaussian5 => [0.06136, 0.24477, 0.38774, 0.24477, 0.06136],
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub(crate) enum SpatialGuideInputTreatment {
    Luma = 0,
    PreserveRgb = 1,
}

impl SpatialGuideInputTreatment {
    pub(crate) fn from_code(code: u32) -> Self {
        match code {
            1 => Self::PreserveRgb,
            _ => Self::Luma,
        }
    }

    pub(crate) fn parse(token: &str) -> Option<Self> {
        match token.trim().to_ascii_lowercase().replace('_', "-").as_str() {
            "luma" | "luminance" | "grayscale" | "native" | "0" => Some(Self::Luma),
            "rgb" | "preserve-rgb" | "rgb-preserve" | "color" | "1" => Some(Self::PreserveRgb),
            _ => None,
        }
    }

    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::Luma => "luma",
            Self::PreserveRgb => "rgb-preserve",
        }
    }

    #[cfg(test)]
    pub(crate) fn apply(self, color: [f32; 3]) -> [f32; 3] {
        match self {
            Self::Luma => {
                let luma =
                    (color[0] * 0.2126 + color[1] * 0.7152 + color[2] * 0.0722).clamp(0.0, 1.0);
                [luma; 3]
            }
            Self::PreserveRgb => color,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum SpatialGuideBlurStage {
    PreHorizontal,
    PreVertical,
    PostHorizontal,
    PostVertical,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct SpatialGuideBlurPassPolicy {
    pub(crate) kernel: SpatialGuideBlurKernel,
    pub(crate) input_treatment: SpatialGuideInputTreatment,
}

impl SpatialGuideBlurPassPolicy {
    pub(crate) fn push_codes(self) -> [f32; 4] {
        [
            self.kernel as u32 as f32,
            self.input_treatment as u32 as f32,
            0.0,
            0.0,
        ]
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct SpatialGuideProcessingPolicy {
    pub(crate) preblur_kernel: SpatialGuideBlurKernel,
    pub(crate) preblur_input: SpatialGuideInputTreatment,
    pub(crate) postblur_kernel: SpatialGuideBlurKernel,
    pub(crate) camera_sampling: SpatialCameraSampling,
}

impl Default for SpatialGuideProcessingPolicy {
    fn default() -> Self {
        Self {
            preblur_kernel: SpatialGuideBlurKernel::NativeBox5,
            preblur_input: SpatialGuideInputTreatment::Luma,
            postblur_kernel: SpatialGuideBlurKernel::NativeBox5,
            camera_sampling: SpatialCameraSampling::ThinLineTent5,
        }
    }
}

impl SpatialGuideProcessingPolicy {
    pub(crate) fn from_codes(
        preblur_kernel: u32,
        preblur_input: u32,
        postblur_kernel: u32,
        camera_sampling: u32,
    ) -> Self {
        Self {
            preblur_kernel: SpatialGuideBlurKernel::from_code(preblur_kernel),
            preblur_input: SpatialGuideInputTreatment::from_code(preblur_input),
            postblur_kernel: SpatialGuideBlurKernel::from_code(postblur_kernel),
            camera_sampling: SpatialCameraSampling::from_code(camera_sampling),
        }
    }

    pub(crate) fn for_stage(self, stage: SpatialGuideBlurStage) -> SpatialGuideBlurPassPolicy {
        match stage {
            SpatialGuideBlurStage::PreHorizontal => SpatialGuideBlurPassPolicy {
                kernel: self.preblur_kernel,
                input_treatment: self.preblur_input,
            },
            SpatialGuideBlurStage::PreVertical => SpatialGuideBlurPassPolicy {
                kernel: self.preblur_kernel,
                input_treatment: SpatialGuideInputTreatment::PreserveRgb,
            },
            SpatialGuideBlurStage::PostHorizontal | SpatialGuideBlurStage::PostVertical => {
                SpatialGuideBlurPassPolicy {
                    kernel: self.postblur_kernel,
                    input_treatment: SpatialGuideInputTreatment::PreserveRgb,
                }
            }
        }
    }

    pub(crate) fn preset_token(self) -> &'static str {
        if self.preblur_kernel == SpatialGuideBlurKernel::NativeBox5
            && self.preblur_input == SpatialGuideInputTreatment::Luma
            && self.postblur_kernel == SpatialGuideBlurKernel::NativeBox5
        {
            "native-parity"
        } else if self.preblur_kernel == SpatialGuideBlurKernel::Gaussian5
            && self.preblur_input == SpatialGuideInputTreatment::PreserveRgb
            && self.postblur_kernel == SpatialGuideBlurKernel::Gaussian5
        {
            "gaussian-rgb-diagnostic"
        } else {
            "custom-ab"
        }
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "publicGuideProcessingPreset={} publicGuidePreblurKernel={} publicGuidePreblurInput={} publicGuidePostblurKernel={} publicGuideKernelAlternatives=native-box5+gaussian5 publicGuideInputAlternatives=luma+rgb-preserve publicCameraSampling={} publicCameraSamplingRadiusTexels={:.2} publicCameraSamplingAlternatives=linear+thin-line-tent5 publicCameraSamplingDefault=thin-line-tent5 publicCameraSamplingFootprintAware=true",
            self.preset_token(),
            self.preblur_kernel.marker_token(),
            self.preblur_input.marker_token(),
            self.postblur_kernel.marker_token(),
            self.camera_sampling.marker_token(),
            self.camera_sampling.radius_texels(),
        )
    }
}

static PREBLUR_KERNEL: AtomicU32 = AtomicU32::new(SpatialGuideBlurKernel::NativeBox5 as u32);
static PREBLUR_INPUT: AtomicU32 = AtomicU32::new(SpatialGuideInputTreatment::Luma as u32);
static POSTBLUR_KERNEL: AtomicU32 = AtomicU32::new(SpatialGuideBlurKernel::NativeBox5 as u32);
static CAMERA_SAMPLING: AtomicU32 = AtomicU32::new(SpatialCameraSampling::ThinLineTent5 as u32);

pub(crate) fn update_spatial_guide_processing_policy(
    policy: SpatialGuideProcessingPolicy,
) -> SpatialGuideProcessingPolicy {
    PREBLUR_KERNEL.store(policy.preblur_kernel as u32, Ordering::Release);
    PREBLUR_INPUT.store(policy.preblur_input as u32, Ordering::Release);
    POSTBLUR_KERNEL.store(policy.postblur_kernel as u32, Ordering::Release);
    CAMERA_SAMPLING.store(policy.camera_sampling as u32, Ordering::Release);
    policy
}

pub(crate) fn current_spatial_guide_processing_policy() -> SpatialGuideProcessingPolicy {
    SpatialGuideProcessingPolicy::from_codes(
        PREBLUR_KERNEL.load(Ordering::Acquire),
        PREBLUR_INPUT.load(Ordering::Acquire),
        POSTBLUR_KERNEL.load(Ordering::Acquire),
        CAMERA_SAMPLING.load(Ordering::Acquire),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn native_parity_is_the_closed_default() {
        let policy = SpatialGuideProcessingPolicy::default();
        assert_eq!(policy.preset_token(), "native-parity");
        assert_eq!(policy.preblur_kernel.weights(), [0.2; 5]);
        assert_eq!(
            policy.for_stage(SpatialGuideBlurStage::PreHorizontal),
            SpatialGuideBlurPassPolicy {
                kernel: SpatialGuideBlurKernel::NativeBox5,
                input_treatment: SpatialGuideInputTreatment::Luma,
            }
        );
        assert_eq!(
            policy
                .for_stage(SpatialGuideBlurStage::PreVertical)
                .input_treatment,
            SpatialGuideInputTreatment::PreserveRgb
        );
    }

    #[test]
    fn gaussian_rgb_remains_a_real_selectable_diagnostic() {
        let policy = SpatialGuideProcessingPolicy::from_codes(1, 1, 1, 0);
        assert_eq!(policy.preset_token(), "gaussian-rgb-diagnostic");
        assert_eq!(
            policy.preblur_kernel.weights(),
            [0.06136, 0.24477, 0.38774, 0.24477, 0.06136]
        );
        assert_eq!(
            policy
                .for_stage(SpatialGuideBlurStage::PreHorizontal)
                .input_treatment,
            SpatialGuideInputTreatment::PreserveRgb
        );
    }

    #[test]
    fn luma_treatment_matches_the_verified_native_coefficients() {
        assert_eq!(
            SpatialGuideInputTreatment::Luma.apply([1.0, 0.0, 0.0]),
            [0.2126; 3]
        );
        assert_eq!(
            SpatialGuideInputTreatment::Luma.apply([0.0, 1.0, 0.0]),
            [0.7152; 3]
        );
        assert_eq!(
            SpatialGuideInputTreatment::Luma.apply([0.0, 0.0, 1.0]),
            [0.0722; 3]
        );
    }

    #[test]
    fn token_parsers_keep_cli_and_panel_policies_closed() {
        assert_eq!(
            SpatialGuideBlurKernel::parse("native_box5"),
            Some(SpatialGuideBlurKernel::NativeBox5)
        );
        assert_eq!(
            SpatialGuideBlurKernel::parse("gaussian"),
            Some(SpatialGuideBlurKernel::Gaussian5)
        );
        assert_eq!(
            SpatialGuideInputTreatment::parse("preserve-rgb"),
            Some(SpatialGuideInputTreatment::PreserveRgb)
        );
        assert_eq!(SpatialGuideBlurKernel::parse("unknown"), None);
        assert_eq!(SpatialGuideInputTreatment::parse("unknown"), None);
        assert_eq!(
            SpatialCameraSampling::parse("thin-line-aa"),
            Some(SpatialCameraSampling::ThinLineTent5)
        );
        assert_eq!(SpatialCameraSampling::parse("unknown"), None);
    }

    #[test]
    fn thin_line_sampling_is_selectable_and_the_candidate_default() {
        let policy = SpatialGuideProcessingPolicy::default();
        assert_eq!(policy.camera_sampling, SpatialCameraSampling::ThinLineTent5);
        assert_eq!(policy.camera_sampling.raw_projection_push_code(), 1.0);
        assert_eq!(policy.camera_sampling.opaque_guide_push_flags(), 2.0);
        assert_eq!(
            SpatialCameraSampling::from_code(0),
            SpatialCameraSampling::Linear
        );
    }
}
