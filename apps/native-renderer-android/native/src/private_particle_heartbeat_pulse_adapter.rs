//! Neutral RR-event pulse handoff for generic private-particle driver slots.
//!
//! The adapter emits a bounded `0.0`/`1.0` event signal. Downstream private
//! compositions own the selected slot's meaning, pulse envelope, baseline,
//! decay, visual mapping, and all selected tuning values.

use crate::{
    native_renderer_properties::{
        PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_MAX_RR_MS,
        PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_MIN_RR_MS,
        PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_MODE,
        PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_STALE_SECONDS,
        PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_TARGET_SLOT,
    },
    native_renderer_property_values::{f32_clamped_value, normalized_property, u32_value},
    polar_composition_adapters::{
        dropped_polar_rr_measurements, polar_rr_after, PolarRrMeasurement, PolarRrPulseSource,
        PolarRrPulseSourceSettings,
    },
};

const DRIVER_SLOT_COUNT: usize = 8;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum HeartbeatPulseAdapterMode {
    Disabled,
    PolarRrEvent,
}

impl HeartbeatPulseAdapterMode {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "polar-rr-event" => Self::PolarRrEvent,
            _ => Self::Disabled,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::PolarRrEvent => "polar-rr-event",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PrivateParticleHeartbeatPulseAdapterSettings {
    mode: HeartbeatPulseAdapterMode,
    target_slot: usize,
    stale_seconds: f32,
    min_rr_ms: f32,
    max_rr_ms: f32,
}

impl PrivateParticleHeartbeatPulseAdapterSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        Self {
            mode: HeartbeatPulseAdapterMode::from_property(lookup(
                PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_MODE,
            )),
            target_slot: u32_value(
                lookup(PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_TARGET_SLOT),
                0,
                0,
                (DRIVER_SLOT_COUNT - 1) as u32,
            ) as usize,
            stale_seconds: f32_clamped_value(
                lookup(PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_STALE_SECONDS),
                5.0,
                0.016,
                120.0,
            ),
            min_rr_ms: f32_clamped_value(
                lookup(PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_MIN_RR_MS),
                1.0,
                1.0,
                60_000.0,
            ),
            max_rr_ms: f32_clamped_value(
                lookup(PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_MAX_RR_MS),
                60_000.0,
                1.0,
                60_000.0,
            ),
        }
    }

    pub(crate) fn disabled() -> Self {
        Self {
            mode: HeartbeatPulseAdapterMode::Disabled,
            target_slot: 0,
            stale_seconds: 5.0,
            min_rr_ms: 1.0,
            max_rr_ms: 60_000.0,
        }
    }

    pub(crate) fn enabled(self) -> bool {
        self.mode == HeartbeatPulseAdapterMode::PolarRrEvent && self.max_rr_ms > self.min_rr_ms
    }

    pub(crate) fn parameter_source(self) -> &'static str {
        if self.enabled() {
            "polar-rr-normalized-event-source"
        } else {
            "particle-payload-build-env"
        }
    }

    fn source_settings(self) -> PolarRrPulseSourceSettings {
        PolarRrPulseSourceSettings {
            enabled: self.enabled(),
            min_rr_ms: self.min_rr_ms,
            max_rr_ms: self.max_rr_ms,
            stale_seconds: self.stale_seconds,
        }
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "privateParticleHeartbeatPulseMode={} privateParticleHeartbeatPulseTargetSlot={} privateParticleHeartbeatPulseStaleSeconds={:.3} privateParticleHeartbeatPulseMinRrMs={:.3} privateParticleHeartbeatPulseMaxRrMs={:.3} privateParticleHeartbeatPulseSourceAuthority={}",
            self.mode.marker_value(),
            self.target_slot,
            self.stale_seconds,
            self.min_rr_ms,
            self.max_rr_ms,
            self.parameter_source(),
        )
    }
}

impl Default for PrivateParticleHeartbeatPulseAdapterSettings {
    fn default() -> Self {
        Self::disabled()
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PrivateParticleHeartbeatPulseAdapter {
    settings: PrivateParticleHeartbeatPulseAdapterSettings,
    source: PolarRrPulseSource,
    value01: f32,
    last_transport_sequence_id: Option<u64>,
    emitted_pulse_count: u64,
}

impl PrivateParticleHeartbeatPulseAdapter {
    pub(crate) fn new(settings: PrivateParticleHeartbeatPulseAdapterSettings) -> Self {
        Self {
            settings,
            source: PolarRrPulseSource::new(settings.source_settings()),
            value01: 0.0,
            last_transport_sequence_id: None,
            emitted_pulse_count: 0,
        }
    }

    pub(crate) fn enabled(self) -> bool {
        self.settings.enabled()
    }

    pub(crate) fn settings(self) -> PrivateParticleHeartbeatPulseAdapterSettings {
        self.settings
    }

    pub(crate) fn update_frame(&mut self, dt_seconds: f32) {
        let measurements = polar_rr_after(self.last_transport_sequence_id);
        self.update_frame_with_measurements(dt_seconds, measurements);
    }

    fn update_frame_with_measurements(
        &mut self,
        dt_seconds: f32,
        measurements: impl IntoIterator<Item = PolarRrMeasurement>,
    ) {
        if !self.enabled() {
            self.value01 = 0.0;
            return;
        }
        self.value01 = 0.0;
        for measurement in measurements {
            self.last_transport_sequence_id = Some(measurement.sequence_id);
            if self.source.push(measurement) && self.source.take_pulse().is_some() {
                self.value01 = 1.0;
                self.emitted_pulse_count = self.emitted_pulse_count.saturating_add(1);
            }
        }
        self.source.advance(dt_seconds);
    }

    pub(crate) fn apply_to_driver_values(self, values01: &mut [f32]) -> bool {
        if !self.enabled() || self.settings.target_slot >= values01.len() {
            return false;
        }
        values01[self.settings.target_slot] = self.value01;
        true
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "{} privateParticleHeartbeatPulseValue01={:.3} privateParticleHeartbeatPulseEmittedCount={} privateParticleHeartbeatPulseLastTransportSequenceId={} privateParticleHeartbeatPulseDroppedTransportMeasurements={} {}",
            self.settings.marker_fields(),
            self.value01,
            self.emitted_pulse_count,
            self.last_transport_sequence_id
                .map(|value| value.to_string())
                .unwrap_or_else(|| "none".to_owned()),
            dropped_polar_rr_measurements(),
            self.source.marker_fields(),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn settings(enabled: bool) -> PrivateParticleHeartbeatPulseAdapterSettings {
        PrivateParticleHeartbeatPulseAdapterSettings {
            mode: if enabled {
                HeartbeatPulseAdapterMode::PolarRrEvent
            } else {
                HeartbeatPulseAdapterMode::Disabled
            },
            target_slot: 5,
            stale_seconds: 2.0,
            min_rr_ms: 200.0,
            max_rr_ms: 3000.0,
        }
    }

    fn measurement(sequence_id: u64, rr_interval_ms: f32) -> PolarRrMeasurement {
        PolarRrMeasurement {
            sequence_id,
            host_time_ns: sequence_id,
            rr_interval_ms,
        }
    }

    #[test]
    fn disabled_adapter_does_not_write() {
        let adapter = PrivateParticleHeartbeatPulseAdapter::new(settings(false));
        let mut values = [0.5; 8];
        assert!(!adapter.apply_to_driver_values(&mut values));
        assert_eq!(values, [0.5; 8]);
    }

    #[test]
    fn valid_event_emits_one_bounded_frame_then_returns_to_zero() {
        let mut adapter = PrivateParticleHeartbeatPulseAdapter::new(settings(true));
        adapter.update_frame_with_measurements(0.01, [measurement(1, 1000.0)]);
        let mut values = [0.0; 8];
        assert!(adapter.apply_to_driver_values(&mut values));
        assert_eq!(values[5], 1.0);
        adapter.update_frame_with_measurements(0.01, []);
        assert!(adapter.apply_to_driver_values(&mut values));
        assert_eq!(values[5], 0.0);
    }

    #[test]
    fn malformed_or_out_of_bounds_event_does_not_emit() {
        for (sequence_id, value) in [(1, f32::NAN), (2, 199.0), (3, 3001.0)] {
            let mut adapter = PrivateParticleHeartbeatPulseAdapter::new(settings(true));
            adapter.update_frame_with_measurements(0.01, [measurement(sequence_id, value)]);
            assert_eq!(adapter.value01, 0.0);
        }
    }

    #[test]
    fn multiple_valid_events_still_produce_a_normalized_output() {
        let mut adapter = PrivateParticleHeartbeatPulseAdapter::new(settings(true));
        adapter
            .update_frame_with_measurements(0.01, [measurement(1, 800.0), measurement(2, 900.0)]);
        assert_eq!(adapter.value01, 1.0);
        assert_eq!(adapter.emitted_pulse_count, 2);
    }
}
