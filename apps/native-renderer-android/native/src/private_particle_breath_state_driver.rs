//! Native breath-state driver adapter for generic private-particle slots.
//!
//! This module owns only the public low-rate scalar adapter: a controller-derived
//! breath phase is integrated into one normalized driver-bank value. Private
//! payloads own the downstream meaning of the selected slot.

use crate::{
    native_controller_breath_state::{NativeControllerBreathPhase, NativeControllerBreathSample},
    native_renderer_properties::{
        PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_EXHALE_SECONDS,
        PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_INHALE_SECONDS,
        PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_MODE,
        PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_TARGET_SLOT,
    },
    native_renderer_property_values::{f32_clamped_value, normalized_property, u32_value},
};

const DEFAULT_RAMP_SECONDS: f32 = 4.0;
const DRIVER_SLOT_COUNT: usize = 8;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PrivateParticleBreathStateDriverMode {
    Disabled,
    DirectControllerState,
}

impl PrivateParticleBreathStateDriverMode {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "direct-controller-state"
            | "native-controller-state"
            | "local-controller-state"
            | "fixed-controller-state" => Self::DirectControllerState,
            _ => Self::Disabled,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::DirectControllerState => "direct-controller-state",
        }
    }

    fn uses_native_controller_state(self) -> bool {
        matches!(self, Self::DirectControllerState)
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PrivateParticleBreathStateDriverSettings {
    mode: PrivateParticleBreathStateDriverMode,
    target_slot: usize,
    inhale_seconds_min_to_max: f32,
    exhale_seconds_max_to_min: f32,
}

impl PrivateParticleBreathStateDriverSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let inhale_seconds_min_to_max = finite_or_default(f32_clamped_value(
            lookup(PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_INHALE_SECONDS),
            DEFAULT_RAMP_SECONDS,
            0.016,
            120.0,
        ));
        let exhale_seconds_max_to_min = finite_or_default(f32_clamped_value(
            lookup(PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_EXHALE_SECONDS),
            DEFAULT_RAMP_SECONDS,
            0.016,
            120.0,
        ));
        Self {
            mode: PrivateParticleBreathStateDriverMode::from_property(lookup(
                PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_MODE,
            )),
            target_slot: u32_value(
                lookup(PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_TARGET_SLOT),
                0,
                0,
                (DRIVER_SLOT_COUNT - 1) as u32,
            ) as usize,
            inhale_seconds_min_to_max,
            exhale_seconds_max_to_min,
        }
    }

    pub(crate) fn disabled() -> Self {
        Self {
            mode: PrivateParticleBreathStateDriverMode::Disabled,
            target_slot: 0,
            inhale_seconds_min_to_max: DEFAULT_RAMP_SECONDS,
            exhale_seconds_max_to_min: DEFAULT_RAMP_SECONDS,
        }
    }

    pub(crate) fn uses_native_controller_state(self) -> bool {
        self.mode.uses_native_controller_state()
    }

    pub(crate) fn enabled(self) -> bool {
        self.uses_native_controller_state()
    }

    pub(crate) fn target_slot(self) -> usize {
        self.target_slot
    }

    pub(crate) fn parameter_source(self) -> &'static str {
        match self.mode {
            PrivateParticleBreathStateDriverMode::Disabled => "particle-payload-build-env",
            PrivateParticleBreathStateDriverMode::DirectControllerState => {
                "native-controller-breath-state-driver"
            }
        }
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "privateParticleBreathStateDriverMode={} privateParticleBreathStateDriverTargetSlot={} privateParticleBreathStateDriverInhaleSecondsMinToMax={:.3} privateParticleBreathStateDriverExhaleSecondsMaxToMin={:.3} privateParticleBreathStateDriverSourceAuthority={}",
            self.mode.marker_value(),
            self.target_slot,
            self.inhale_seconds_min_to_max,
            self.exhale_seconds_max_to_min,
            self.parameter_source(),
        )
    }
}

impl Default for PrivateParticleBreathStateDriverSettings {
    fn default() -> Self {
        Self::disabled()
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PrivateParticleBreathStateDriver {
    settings: PrivateParticleBreathStateDriverSettings,
    value01: f32,
    phase: NativeControllerBreathPhase,
    last_sequence_id: Option<u64>,
    received_samples: u64,
    age_seconds: f32,
}

impl PrivateParticleBreathStateDriver {
    pub(crate) fn new(
        settings: PrivateParticleBreathStateDriverSettings,
        initial_value01: f32,
    ) -> Self {
        Self {
            settings,
            value01: initial_value01.clamp(0.0, 1.0),
            phase: NativeControllerBreathPhase::Unknown,
            last_sequence_id: None,
            received_samples: 0,
            age_seconds: 0.0,
        }
    }

    pub(crate) fn settings(self) -> PrivateParticleBreathStateDriverSettings {
        self.settings
    }

    pub(crate) fn enabled(self) -> bool {
        self.settings.enabled()
    }

    pub(crate) fn apply_sample(&mut self, sample: NativeControllerBreathSample) {
        if !self.enabled() || self.last_sequence_id == Some(sample.sequence_id) {
            return;
        }
        self.phase = sample.phase;
        self.last_sequence_id = Some(sample.sequence_id);
        self.received_samples = self.received_samples.saturating_add(1);
        self.age_seconds = 0.0;
    }

    pub(crate) fn update_frame(&mut self, dt_seconds: f32) {
        if !self.enabled() {
            return;
        }
        let dt_seconds = sanitize_dt(dt_seconds);
        self.age_seconds = (self.age_seconds + dt_seconds).min(3600.0);
        match self.phase {
            NativeControllerBreathPhase::Inhale => {
                self.value01 = (self.value01
                    + delta_for_seconds(dt_seconds, self.settings.inhale_seconds_min_to_max))
                .clamp(0.0, 1.0);
            }
            NativeControllerBreathPhase::Exhale => {
                self.value01 = (self.value01
                    - delta_for_seconds(dt_seconds, self.settings.exhale_seconds_max_to_min))
                .clamp(0.0, 1.0);
            }
            NativeControllerBreathPhase::Unknown
            | NativeControllerBreathPhase::Pause
            | NativeControllerBreathPhase::BadTracking => {}
        }
    }

    pub(crate) fn apply_to_driver_values(&self, values01: &mut [f32]) -> bool {
        if !self.enabled() || self.settings.target_slot >= values01.len() {
            return false;
        }
        values01[self.settings.target_slot] = self.value01;
        true
    }

    pub(crate) fn value01(self) -> f32 {
        self.value01
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "{} privateParticleBreathStateDriverValue01={:.3} privateParticleBreathStateDriverPhase={} privateParticleBreathStateDriverLastSequenceId={} privateParticleBreathStateDriverAgeMs={} privateParticleBreathStateDriverReceivedSamples={}",
            self.settings.marker_fields(),
            self.value01,
            self.phase.marker_value(),
            self.last_sequence_id
                .map(|sequence_id| sequence_id.to_string())
                .unwrap_or_else(|| "none".to_owned()),
            (self.age_seconds * 1000.0).round() as u64,
            self.received_samples,
        )
    }
}

fn finite_or_default(value: f32) -> f32 {
    if value.is_finite() {
        value
    } else {
        DEFAULT_RAMP_SECONDS
    }
}

fn sanitize_dt(dt_seconds: f32) -> f32 {
    if dt_seconds.is_finite() && dt_seconds > 0.0 {
        dt_seconds.min(1.0)
    } else {
        0.0
    }
}

fn delta_for_seconds(dt_seconds: f32, seconds: f32) -> f32 {
    if seconds.is_finite() && seconds > 0.0 {
        dt_seconds / seconds
    } else {
        0.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn settings() -> PrivateParticleBreathStateDriverSettings {
        PrivateParticleBreathStateDriverSettings {
            mode: PrivateParticleBreathStateDriverMode::DirectControllerState,
            target_slot: 0,
            inhale_seconds_min_to_max: 4.0,
            exhale_seconds_max_to_min: 2.0,
        }
    }

    #[test]
    fn parses_direct_controller_driver_settings() {
        let settings = PrivateParticleBreathStateDriverSettings::from_property_lookup(|name| {
            match name {
                "debug.rustyquest.native_renderer.private_particles.breath_state_driver.mode" => {
                    Some("native-controller-state".to_owned())
                }
                "debug.rustyquest.native_renderer.private_particles.breath_state_driver.target_slot" => {
                    Some("7".to_owned())
                }
                _ => None,
            }
        });
        assert!(settings.uses_native_controller_state());
        assert_eq!(settings.target_slot(), 7);
    }

    #[test]
    fn state_ramp_is_time_based_and_writes_selected_slot() {
        let mut driver = PrivateParticleBreathStateDriver::new(settings(), 0.5);
        driver.apply_sample(NativeControllerBreathSample {
            phase: NativeControllerBreathPhase::Inhale,
            sequence_id: 1,
        });
        driver.update_frame(1.0);
        assert!((driver.value01() - 0.75).abs() < 0.0001);

        driver.apply_sample(NativeControllerBreathSample {
            phase: NativeControllerBreathPhase::Exhale,
            sequence_id: 2,
        });
        driver.update_frame(0.5);
        assert!((driver.value01() - 0.5).abs() < 0.0001);

        let mut values = [0.0; 8];
        assert!(driver.apply_to_driver_values(&mut values));
        assert!((values[0] - 0.5).abs() < 0.0001);
    }

    #[test]
    fn pause_and_bad_tracking_hold_last_value() {
        let mut driver = PrivateParticleBreathStateDriver::new(settings(), 0.25);
        for phase in [
            NativeControllerBreathPhase::Pause,
            NativeControllerBreathPhase::BadTracking,
        ] {
            driver.apply_sample(NativeControllerBreathSample {
                phase,
                sequence_id: 10,
            });
            driver.update_frame(1.0);
            assert!((driver.value01() - 0.25).abs() < 0.0001);
        }
    }
}
