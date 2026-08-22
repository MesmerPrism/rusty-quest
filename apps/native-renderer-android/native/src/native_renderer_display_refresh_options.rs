//! App-owned OpenXR display-refresh request and effective-state fencing.
//!
//! The native renderer normally leaves the runtime display refresh rate alone.
//! A closed private performance profile may opt in to the one supported request
//! in this app: 72 Hz.  The state machine keeps the request, observed effective
//! rate, and runtime rate-change events bound to the current OpenXR session
//! generation so stale evidence cannot satisfy a later run.

use std::cmp::Ordering;

use crate::{
    native_renderer_properties::PROP_OPENXR_DISPLAY_REFRESH_RATE_HZ,
    native_renderer_property_values::normalized_property,
};

pub(crate) const REQUESTED_DISPLAY_REFRESH_RATE_HZ: f32 = 72.0;
const DISPLAY_REFRESH_RATE_TOLERANCE_HZ: f32 = 0.01;

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) enum NativeDisplayRefreshRequest {
    Unset,
    Hz72,
    Invalid(String),
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct NativeDisplayRefreshSettings {
    request: NativeDisplayRefreshRequest,
}

impl NativeDisplayRefreshSettings {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        let request = match normalized_property(value) {
            value if value.is_empty() => NativeDisplayRefreshRequest::Unset,
            value if value == "72" => NativeDisplayRefreshRequest::Hz72,
            value => NativeDisplayRefreshRequest::Invalid(value),
        };
        Self { request }
    }

    pub(crate) fn requested_hz(&self) -> Option<f32> {
        match self.request {
            NativeDisplayRefreshRequest::Hz72 => Some(REQUESTED_DISPLAY_REFRESH_RATE_HZ),
            NativeDisplayRefreshRequest::Unset | NativeDisplayRefreshRequest::Invalid(_) => None,
        }
    }

    #[cfg(test)]
    pub(crate) fn request(&self) -> &NativeDisplayRefreshRequest {
        &self.request
    }

    pub(crate) fn extension_requested(&self) -> bool {
        self.requested_hz().is_some()
    }

    pub(crate) fn validate(&self) -> Result<(), String> {
        match &self.request {
            NativeDisplayRefreshRequest::Unset | NativeDisplayRefreshRequest::Hz72 => Ok(()),
            NativeDisplayRefreshRequest::Invalid(value) => Err(format!(
                "{} must be unset or the closed supported value 72; got {}",
                PROP_OPENXR_DISPLAY_REFRESH_RATE_HZ, value
            )),
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        match &self.request {
            NativeDisplayRefreshRequest::Unset => format!(
                "displayRefreshProperty={} displayRefreshRequestedHz=unset displayRefreshRequestState=unset",
                PROP_OPENXR_DISPLAY_REFRESH_RATE_HZ
            ),
            NativeDisplayRefreshRequest::Hz72 => format!(
                "displayRefreshProperty={} displayRefreshRequestedHz={:.3} displayRefreshRequestState=requested",
                PROP_OPENXR_DISPLAY_REFRESH_RATE_HZ,
                REQUESTED_DISPLAY_REFRESH_RATE_HZ
            ),
            NativeDisplayRefreshRequest::Invalid(value) => format!(
                "displayRefreshProperty={} displayRefreshRequestedHz=invalid displayRefreshRequestState=invalid displayRefreshInvalidValue={}",
                PROP_OPENXR_DISPLAY_REFRESH_RATE_HZ,
                value
            ),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum DisplayRefreshRequestOutcome {
    NotAttempted,
    Accepted,
    Rejected,
}

impl DisplayRefreshRequestOutcome {
    fn marker_value(self) -> &'static str {
        match self {
            Self::NotAttempted => "not-attempted",
            Self::Accepted => "accepted",
            Self::Rejected => "rejected",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
struct DisplayRefreshRateChange {
    from_hz: f32,
    to_hz: f32,
}

#[derive(Clone, Debug)]
pub(crate) struct NativeDisplayRefreshRuntimeState {
    settings: NativeDisplayRefreshSettings,
    session_generation: u64,
    supported_hz: Vec<f32>,
    request_outcome: DisplayRefreshRequestOutcome,
    effective_hz: Option<f32>,
    latest_rate_change: Option<DisplayRefreshRateChange>,
}

impl NativeDisplayRefreshRuntimeState {
    pub(crate) fn new(settings: NativeDisplayRefreshSettings) -> Self {
        Self {
            settings,
            session_generation: 0,
            supported_hz: Vec::new(),
            request_outcome: DisplayRefreshRequestOutcome::NotAttempted,
            effective_hz: None,
            latest_rate_change: None,
        }
    }

    pub(crate) fn requested(&self) -> bool {
        self.settings.extension_requested()
    }

    pub(crate) fn begin_session(&mut self) -> u64 {
        self.session_generation = self.session_generation.saturating_add(1);
        self.supported_hz.clear();
        self.request_outcome = DisplayRefreshRequestOutcome::NotAttempted;
        self.effective_hz = None;
        self.latest_rate_change = None;
        self.session_generation
    }

    pub(crate) fn session_generation(&self) -> u64 {
        self.session_generation
    }

    pub(crate) fn record_supported_rates(&mut self, generation: u64, rates: &[f32]) -> bool {
        if !self.accepts_generation(generation) {
            return false;
        }
        self.supported_hz = rates
            .iter()
            .copied()
            .filter(|rate| rate.is_finite() && *rate > 0.0)
            .collect();
        self.supported_hz
            .sort_by(|left, right| left.partial_cmp(right).unwrap_or(Ordering::Equal));
        self.supported_hz
            .dedup_by(|left, right| rate_matches(*left, *right));
        true
    }

    pub(crate) fn requested_rate_is_supported(&self) -> bool {
        self.settings.requested_hz().is_some_and(|requested| {
            self.supported_hz
                .iter()
                .copied()
                .any(|supported| rate_matches(supported, requested))
        })
    }

    pub(crate) fn record_request_result(&mut self, generation: u64, accepted: bool) -> bool {
        if !self.accepts_generation(generation) {
            return false;
        }
        self.request_outcome = if accepted {
            DisplayRefreshRequestOutcome::Accepted
        } else {
            DisplayRefreshRequestOutcome::Rejected
        };
        true
    }

    pub(crate) fn record_effective_rate(&mut self, generation: u64, rate_hz: f32) -> bool {
        if !self.accepts_generation(generation) {
            return false;
        }
        self.effective_hz = rate_hz.is_finite().then_some(rate_hz);
        true
    }

    pub(crate) fn record_rate_change(&mut self, generation: u64, from_hz: f32, to_hz: f32) -> bool {
        if !self.accepts_generation(generation) {
            return false;
        }
        self.latest_rate_change = (from_hz.is_finite() && to_hz.is_finite())
            .then_some(DisplayRefreshRateChange { from_hz, to_hz });
        true
    }

    pub(crate) fn ensure_performance_ready(&self) -> Result<(), String> {
        let Some(requested_hz) = self.settings.requested_hz() else {
            return Ok(());
        };
        if self.session_generation == 0 {
            return Err(
                "display refresh request has no current OpenXR session generation".to_string(),
            );
        }
        if !self.requested_rate_is_supported() {
            return Err(format!(
                "requested display refresh {:.3} Hz is absent from supported rates {}",
                requested_hz,
                self.supported_rates_marker()
            ));
        }
        if self.request_outcome != DisplayRefreshRequestOutcome::Accepted {
            return Err(format!(
                "display refresh request {:.3} Hz was not accepted",
                requested_hz
            ));
        }
        let Some(effective_hz) = self.effective_hz else {
            return Err(format!(
                "display refresh request {:.3} Hz has no effective-rate readback",
                requested_hz
            ));
        };
        if !rate_matches(effective_hz, requested_hz) {
            return Err(format!(
                "display refresh effective rate {:.3} Hz does not match requested {:.3} Hz",
                effective_hz, requested_hz
            ));
        }
        Ok(())
    }

    pub(crate) fn marker_fields(&self) -> String {
        let effective_hz = self
            .effective_hz
            .map(|rate| format!("{rate:.3}"))
            .unwrap_or_else(|| "unavailable".to_string());
        let (change_from_hz, change_to_hz) = self
            .latest_rate_change
            .map(|change| {
                (
                    format!("{:.3}", change.from_hz),
                    format!("{:.3}", change.to_hz),
                )
            })
            .unwrap_or_else(|| ("none".to_string(), "none".to_string()));
        format!(
            "{} displayRefreshSessionGeneration={} displayRefreshSupportedHz={} displayRefreshRequestResult={} displayRefreshEffectiveHz={} displayRefreshRateChangeFromHz={} displayRefreshRateChangeToHz={} displayRefreshPerformanceReady={}",
            self.settings.marker_fields(),
            self.session_generation,
            self.supported_rates_marker(),
            self.request_outcome.marker_value(),
            effective_hz,
            change_from_hz,
            change_to_hz,
            self.ensure_performance_ready().is_ok(),
        )
    }

    fn accepts_generation(&self, generation: u64) -> bool {
        generation != 0 && generation == self.session_generation
    }

    fn supported_rates_marker(&self) -> String {
        if self.supported_hz.is_empty() {
            "none".to_string()
        } else {
            self.supported_hz
                .iter()
                .map(|rate| format!("{rate:.3}"))
                .collect::<Vec<_>>()
                .join(",")
        }
    }
}

fn rate_matches(observed_hz: f32, expected_hz: f32) -> bool {
    observed_hz.is_finite()
        && expected_hz.is_finite()
        && (observed_hz - expected_hz).abs() <= DISPLAY_REFRESH_RATE_TOLERANCE_HZ
}

#[cfg(test)]
mod tests {
    use super::*;

    fn requested_settings() -> NativeDisplayRefreshSettings {
        NativeDisplayRefreshSettings::from_property(Some("72".to_string()))
    }

    #[test]
    fn default_is_unset_and_does_not_request_the_extension() {
        let settings = NativeDisplayRefreshSettings::from_property(None);
        assert_eq!(settings.request, NativeDisplayRefreshRequest::Unset);
        assert_eq!(settings.requested_hz(), None);
        assert!(!settings.extension_requested());
        assert!(settings.validate().is_ok());
    }

    #[test]
    fn invalid_value_is_fail_closed_instead_of_falling_back_to_unset() {
        let settings = NativeDisplayRefreshSettings::from_property(Some("90".to_string()));
        assert_eq!(
            settings.request,
            NativeDisplayRefreshRequest::Invalid("90".to_string())
        );
        assert!(!settings.extension_requested());
        assert!(settings.validate().is_err());
        assert!(settings
            .marker_fields()
            .contains("displayRefreshRequestState=invalid"));
    }

    #[test]
    fn supported_72_request_with_effective_readback_is_performance_ready() {
        let mut state = NativeDisplayRefreshRuntimeState::new(requested_settings());
        let generation = state.begin_session();
        assert_eq!(generation, 1);
        assert!(state.record_supported_rates(generation, &[90.0, 72.0005, 72.0]));
        assert!(state.requested_rate_is_supported());
        assert!(state.record_request_result(generation, true));
        assert!(state.record_effective_rate(generation, 72.0));
        assert!(state.ensure_performance_ready().is_ok());
        let marker = state.marker_fields();
        assert!(marker.contains("displayRefreshSessionGeneration=1"));
        assert!(marker.contains("displayRefreshRequestedHz=72.000"));
        assert!(marker.contains("displayRefreshSupportedHz=72.000,90.000"));
        assert!(marker.contains("displayRefreshRequestResult=accepted"));
        assert!(marker.contains("displayRefreshEffectiveHz=72.000"));
        assert!(marker.contains("displayRefreshPerformanceReady=true"));
    }

    #[test]
    fn unavailable_72_or_effective_mismatch_fails_performance_readiness() {
        let mut unavailable = NativeDisplayRefreshRuntimeState::new(requested_settings());
        let generation = unavailable.begin_session();
        assert!(unavailable.record_supported_rates(generation, &[80.0, 90.0]));
        assert!(unavailable.record_request_result(generation, true));
        assert!(unavailable.record_effective_rate(generation, 80.0));
        assert!(unavailable.ensure_performance_ready().is_err());

        let mut mismatch = NativeDisplayRefreshRuntimeState::new(requested_settings());
        let generation = mismatch.begin_session();
        assert!(mismatch.record_supported_rates(generation, &[72.0, 90.0]));
        assert!(mismatch.record_request_result(generation, true));
        assert!(mismatch.record_effective_rate(generation, 80.0));
        assert!(mismatch.ensure_performance_ready().is_err());
        assert!(mismatch
            .marker_fields()
            .contains("displayRefreshPerformanceReady=false"));
    }

    #[test]
    fn rate_change_is_marked_and_requires_a_fresh_matching_readback() {
        let mut state = NativeDisplayRefreshRuntimeState::new(requested_settings());
        let generation = state.begin_session();
        assert!(state.record_supported_rates(generation, &[72.0, 90.0]));
        assert!(state.record_request_result(generation, true));
        assert!(state.record_effective_rate(generation, 72.0));
        assert!(state.ensure_performance_ready().is_ok());

        assert!(state.record_rate_change(generation, 72.0, 90.0));
        assert!(state.record_effective_rate(generation, 90.0));
        assert!(state.ensure_performance_ready().is_err());
        let marker = state.marker_fields();
        assert!(marker.contains("displayRefreshRateChangeFromHz=72.000"));
        assert!(marker.contains("displayRefreshRateChangeToHz=90.000"));
        assert!(marker.contains("displayRefreshEffectiveHz=90.000"));
    }

    #[test]
    fn prior_session_replay_cannot_satisfy_a_new_session_generation() {
        let mut state = NativeDisplayRefreshRuntimeState::new(requested_settings());
        let first_generation = state.begin_session();
        assert!(state.record_supported_rates(first_generation, &[72.0]));
        assert!(state.record_request_result(first_generation, true));
        assert!(state.record_effective_rate(first_generation, 72.0));
        assert!(state.ensure_performance_ready().is_ok());

        let second_generation = state.begin_session();
        assert_eq!(second_generation, first_generation + 1);
        assert!(!state.record_supported_rates(first_generation, &[72.0]));
        assert!(!state.record_request_result(first_generation, true));
        assert!(!state.record_effective_rate(first_generation, 72.0));
        assert!(!state.record_rate_change(first_generation, 72.0, 72.0));
        assert!(state.ensure_performance_ready().is_err());
        assert!(state
            .marker_fields()
            .contains("displayRefreshSessionGeneration=2"));
        assert!(state
            .marker_fields()
            .contains("displayRefreshSupportedHz=none"));
    }
}
