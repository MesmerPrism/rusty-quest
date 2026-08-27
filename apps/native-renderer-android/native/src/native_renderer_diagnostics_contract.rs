//! Public, payload-neutral build policy for renderer diagnostics.
//!
//! Metric definitions and reduction shaders remain private payload concerns.

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum DiagnosticsLevel {
    Off,
    Basic,
    Detailed,
}

impl DiagnosticsLevel {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::Basic => "basic",
            Self::Detailed => "detailed",
        }
    }

    pub(crate) const fn timestamps_enabled(self) -> bool {
        !matches!(self, Self::Off)
    }

    pub(crate) const fn detailed_readback_enabled(self) -> bool {
        matches!(self, Self::Detailed)
    }
}

#[cfg(gpu_diagnostics_off)]
pub(crate) const BUILD_DIAGNOSTICS_LEVEL: DiagnosticsLevel = DiagnosticsLevel::Off;
#[cfg(gpu_diagnostics_basic)]
pub(crate) const BUILD_DIAGNOSTICS_LEVEL: DiagnosticsLevel = DiagnosticsLevel::Basic;
#[cfg(gpu_diagnostics_detailed)]
pub(crate) const BUILD_DIAGNOSTICS_LEVEL: DiagnosticsLevel = DiagnosticsLevel::Detailed;

pub(crate) const DIAGNOSTIC_SCHEMA_V2: u32 = 2;
pub(crate) const DIAGNOSTIC_HEALTH_WINDOW_SAMPLES: usize = 120;

pub(crate) fn build_policy_marker_fields() -> String {
    format!(
        "gpuDiagnosticsSchema=v{} gpuDiagnosticsLevel={} gpuDiagnosticsTimestampPolicy={} gpuDiagnosticsReadbackPolicy={} gpuDiagnosticsHealthWindowSamples={}",
        DIAGNOSTIC_SCHEMA_V2,
        BUILD_DIAGNOSTICS_LEVEL.as_str(),
        BUILD_DIAGNOSTICS_LEVEL.timestamps_enabled(),
        BUILD_DIAGNOSTICS_LEVEL.detailed_readback_enabled(),
        DIAGNOSTIC_HEALTH_WINDOW_SAMPLES,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn levels_are_closed_and_have_distinct_instrumentation_policies() {
        assert!(!DiagnosticsLevel::Off.timestamps_enabled());
        assert!(DiagnosticsLevel::Basic.timestamps_enabled());
        assert!(!DiagnosticsLevel::Basic.detailed_readback_enabled());
        assert!(DiagnosticsLevel::Detailed.detailed_readback_enabled());
        assert!(build_policy_marker_fields().contains("gpuDiagnosticsSchema=v2"));
    }
}
