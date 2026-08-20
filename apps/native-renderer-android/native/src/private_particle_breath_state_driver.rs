//! Native breath-state driver adapter for generic private-particle slots.
//!
//! This module owns only the public low-rate scalar adapter: a controller-derived
//! breath phase is integrated into one normalized driver-bank value. Private
//! payloads own the downstream meaning of the selected slot.

use rusty_quest_breath_contract::assessment::CommonBreathPhase;

use crate::{
    breath_input_selection::{BreathInputSelection, ControllerVolumeKind},
    native_controller_breath_state::NativeControllerBreathSample,
    native_renderer_properties::{
        PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_ACC_AXIS,
        PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_ACC_MAX_MG,
        PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_ACC_MIN_MG,
        PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_ACC_STALE_SECONDS,
        PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_EXHALE_SECONDS,
        PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_INHALE_SECONDS,
        PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_MODE,
        PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_TARGET_SLOT,
    },
    native_renderer_property_values::{f32_clamped_value, normalized_property, u32_value},
    polar_composition_adapters::{
        latest_polar_acc_after, PolarAccAxis, PolarAccBreathSource, PolarAccBreathSourceSettings,
    },
};

const DEFAULT_RAMP_SECONDS: f32 = 4.0;
const DRIVER_SLOT_COUNT: usize = 8;

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PrivateParticleBreathStateDriverSettings {
    selection: BreathInputSelection,
    target_slot: usize,
    inhale_seconds_min_to_max: f32,
    exhale_seconds_max_to_min: f32,
    acc_axis: PolarAccAxis,
    acc_min_mg: f32,
    acc_max_mg: f32,
    acc_stale_seconds: f32,
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
            selection: BreathInputSelection::from_legacy_mode_property(lookup(
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
            acc_axis: PolarAccAxis::from_token(&normalized_property(lookup(
                PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_ACC_AXIS,
            ))),
            acc_min_mg: f32_clamped_value(
                lookup(PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_ACC_MIN_MG),
                -2000.0,
                -16_000.0,
                16_000.0,
            ),
            acc_max_mg: f32_clamped_value(
                lookup(PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_ACC_MAX_MG),
                2000.0,
                -16_000.0,
                16_000.0,
            ),
            acc_stale_seconds: f32_clamped_value(
                lookup(PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_ACC_STALE_SECONDS),
                2.0,
                0.016,
                120.0,
            ),
        }
    }

    pub(crate) fn disabled() -> Self {
        Self {
            selection: BreathInputSelection::disabled(),
            target_slot: 0,
            inhale_seconds_min_to_max: DEFAULT_RAMP_SECONDS,
            exhale_seconds_max_to_min: DEFAULT_RAMP_SECONDS,
            acc_axis: PolarAccAxis::X,
            acc_min_mg: -2000.0,
            acc_max_mg: 2000.0,
            acc_stale_seconds: 2.0,
        }
    }

    pub(crate) fn uses_native_controller_state(self) -> bool {
        self.selection.uses_controller_state()
    }

    pub(crate) fn uses_native_controller_assessment(self) -> bool {
        self.selection.uses_controller_state() || self.selection.uses_controller_volume()
    }

    pub(crate) fn controller_volume_kind(self) -> Option<ControllerVolumeKind> {
        self.selection.controller_volume_kind()
    }

    pub(crate) fn enabled(self) -> bool {
        self.uses_native_controller_state()
            || (self.selection.uses_polar_acc_volume() && self.acc_max_mg > self.acc_min_mg)
    }

    pub(crate) fn target_slot(self) -> usize {
        self.target_slot
    }

    pub(crate) fn parameter_source(self) -> &'static str {
        if self.selection.uses_controller_state() {
            "native-controller-breath-state-driver"
        } else if self.selection.uses_controller_volume() {
            "native-controller-breath-volume-assessment"
        } else if self.selection.uses_polar_acc_volume() {
            "polar-acc-normalized-breath-source"
        } else if self.selection.status_marker() == "disabled" {
            "particle-payload-build-env"
        } else {
            "inert-rejected-breath-selection"
        }
    }

    fn polar_acc_source_settings(self) -> PolarAccBreathSourceSettings {
        PolarAccBreathSourceSettings {
            enabled: self.selection.uses_polar_acc_volume() && self.acc_max_mg > self.acc_min_mg,
            axis: self.acc_axis,
            input_min_mg: self.acc_min_mg,
            input_max_mg: self.acc_max_mg,
            stale_seconds: self.acc_stale_seconds,
        }
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "privateParticleBreathStateDriverMode={} privateParticleBreathInputSource={} privateParticleBreathMapping={} privateParticleBreathSelectionStatus={} privateParticleBreathStateDriverTargetSlot={} privateParticleBreathStateDriverInhaleSecondsMinToMax={:.3} privateParticleBreathStateDriverExhaleSecondsMaxToMin={:.3} privateParticleBreathStateDriverAccAxis={} privateParticleBreathStateDriverAccMinMg={:.3} privateParticleBreathStateDriverAccMaxMg={:.3} privateParticleBreathStateDriverAccStaleSeconds={:.3} privateParticleBreathStateDriverSourceAuthority={}",
            self.selection.effective_mode_marker(),
            self.selection.source_marker(),
            self.selection.mapping_marker(),
            self.selection.status_marker(),
            self.target_slot,
            self.inhale_seconds_min_to_max,
            self.exhale_seconds_max_to_min,
            self.acc_axis.marker_value(),
            self.acc_min_mg,
            self.acc_max_mg,
            self.acc_stale_seconds,
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
    phase: CommonBreathPhase,
    last_sequence_id: Option<u64>,
    received_samples: u64,
    age_seconds: f32,
    polar_acc_source: PolarAccBreathSource,
    last_polar_acc_transport_sequence_id: Option<u64>,
}

impl PrivateParticleBreathStateDriver {
    pub(crate) fn new(
        settings: PrivateParticleBreathStateDriverSettings,
        initial_value01: f32,
    ) -> Self {
        Self {
            settings,
            value01: initial_value01.clamp(0.0, 1.0),
            phase: CommonBreathPhase::Unknown,
            last_sequence_id: None,
            received_samples: 0,
            age_seconds: 0.0,
            polar_acc_source: PolarAccBreathSource::new(settings.polar_acc_source_settings()),
            last_polar_acc_transport_sequence_id: None,
        }
    }

    pub(crate) fn settings(self) -> PrivateParticleBreathStateDriverSettings {
        self.settings
    }

    pub(crate) fn enabled(self) -> bool {
        self.settings.enabled()
    }

    pub(crate) fn apply_sample(&mut self, sample: NativeControllerBreathSample) {
        if !self.settings.uses_native_controller_state()
            || self.last_sequence_id == Some(sample.sequence_id)
        {
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
        if self.settings.selection.uses_polar_acc_volume() {
            if let Some(measurement) =
                latest_polar_acc_after(self.last_polar_acc_transport_sequence_id)
            {
                self.last_polar_acc_transport_sequence_id = Some(measurement.sequence_id);
                if self.polar_acc_source.push(measurement) {
                    if let Some(sample) = self.polar_acc_source.sample() {
                        self.value01 = sample.value01;
                        self.received_samples = self.received_samples.saturating_add(1);
                        self.age_seconds = 0.0;
                    }
                }
            }
            self.polar_acc_source.advance(dt_seconds);
            return;
        }
        match self.phase {
            CommonBreathPhase::Inhale => {
                self.value01 = (self.value01
                    + delta_for_seconds(dt_seconds, self.settings.inhale_seconds_min_to_max))
                .clamp(0.0, 1.0);
            }
            CommonBreathPhase::Exhale => {
                self.value01 = (self.value01
                    - delta_for_seconds(dt_seconds, self.settings.exhale_seconds_max_to_min))
                .clamp(0.0, 1.0);
            }
            CommonBreathPhase::Unknown
            | CommonBreathPhase::Hold
            | CommonBreathPhase::BadTracking => {}
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
            "{} privateParticleBreathStateDriverValue01={:.3} privateParticleBreathStateDriverPhase={} privateParticleBreathStateDriverLastSequenceId={} privateParticleBreathStateDriverAgeMs={} privateParticleBreathStateDriverReceivedSamples={} privateParticleBreathStateDriverLastPolarAccTransportSequenceId={} {}",
            self.settings.marker_fields(),
            self.value01,
            self.phase.as_str(),
            self.last_sequence_id
                .map(|sequence_id| sequence_id.to_string())
                .unwrap_or_else(|| "none".to_owned()),
            (self.age_seconds * 1000.0).round() as u64,
            self.received_samples,
            self.last_polar_acc_transport_sequence_id
                .map(|sequence_id| sequence_id.to_string())
                .unwrap_or_else(|| "none".to_owned()),
            self.polar_acc_source.marker_fields(),
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
            selection: BreathInputSelection::from_parts(
                Some("controller".to_owned()),
                Some("state".to_owned()),
            ),
            target_slot: 0,
            inhale_seconds_min_to_max: 4.0,
            exhale_seconds_max_to_min: 2.0,
            acc_axis: PolarAccAxis::X,
            acc_min_mg: -100.0,
            acc_max_mg: 100.0,
            acc_stale_seconds: 1.0,
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
    fn unavailable_and_unknown_modes_are_rejected_and_inert() {
        for mode in ["polar-acc-state", "unknown-mode"] {
            let settings = PrivateParticleBreathStateDriverSettings::from_property_lookup(|name| {
                (name == "debug.rustyquest.native_renderer.private_particles.breath_state_driver.mode")
                    .then(|| mode.to_owned())
            });
            assert!(!settings.enabled());
            assert_ne!(settings.selection.status_marker(), "disabled");
            assert_eq!(
                settings.parameter_source(),
                "inert-rejected-breath-selection"
            );

            let driver = PrivateParticleBreathStateDriver::new(settings, 0.5);
            let mut values = [0.25; 8];
            assert!(!driver.apply_to_driver_values(&mut values));
            assert_eq!(values, [0.25; 8]);
        }
    }

    #[test]
    fn controller_volume_assessment_is_selected_without_private_driver_mapping() {
        for mode in [
            "direct-controller-volume-fixed-orientation",
            "direct-controller-volume-dynamic-motion-axis",
        ] {
            let settings = PrivateParticleBreathStateDriverSettings::from_property_lookup(|name| {
                (name == "debug.rustyquest.native_renderer.private_particles.breath_state_driver.mode")
                    .then(|| mode.to_owned())
            });
            assert!(settings.uses_native_controller_assessment());
            assert!(settings.controller_volume_kind().is_some());
            assert!(!settings.enabled());
            assert_eq!(
                settings.parameter_source(),
                "native-controller-breath-volume-assessment"
            );

            let driver = PrivateParticleBreathStateDriver::new(settings, 0.5);
            let mut values = [0.25; 8];
            assert!(!driver.apply_to_driver_values(&mut values));
            assert_eq!(values, [0.25; 8]);
        }
    }

    #[test]
    fn state_ramp_is_time_based_and_writes_selected_slot() {
        let mut driver = PrivateParticleBreathStateDriver::new(settings(), 0.5);
        driver.apply_sample(NativeControllerBreathSample {
            phase: CommonBreathPhase::Inhale,
            sequence_id: 1,
        });
        driver.update_frame(1.0);
        assert!((driver.value01() - 0.75).abs() < 0.0001);

        driver.apply_sample(NativeControllerBreathSample {
            phase: CommonBreathPhase::Exhale,
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
        for phase in [CommonBreathPhase::Hold, CommonBreathPhase::BadTracking] {
            driver.apply_sample(NativeControllerBreathSample {
                phase,
                sequence_id: 10,
            });
            driver.update_frame(1.0);
            assert!((driver.value01() - 0.25).abs() < 0.0001);
        }
    }
}
